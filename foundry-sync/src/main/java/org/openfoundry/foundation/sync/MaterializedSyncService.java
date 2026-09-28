package org.openfoundry.foundation.sync;

import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.*;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Authorized source ingestion: facts, provenance, event receipts and partition checkpoints commit together. */
public final class MaterializedSyncService {
    public enum UnknownOrigins { REJECT_CHANGES, TRUST_UPDATED_AT }

    private final StorageProvider storage;
    private final ConflictResolver conflicts;
    private final SyncAuthorizer authorizer;
    private final UnknownOrigins unknownOrigins;
    private final Clock clock;
    private final TransformRegistry transforms;

    public MaterializedSyncService(StorageProvider storage) {
        this(storage, new ConflictResolver(ConflictResolver.Strategy.LAST_WRITE_WINS, Map.of(), Map.of()));
    }

    public MaterializedSyncService(StorageProvider storage, ConflictResolver conflicts) {
        this(storage, conflicts, SyncAuthorizer.denyAll(), UnknownOrigins.REJECT_CHANGES, Clock.systemUTC(), new TransformRegistry());
    }

    private MaterializedSyncService(StorageProvider storage, ConflictResolver conflicts, SyncAuthorizer authorizer, UnknownOrigins unknownOrigins, Clock clock, TransformRegistry transforms) {
        this.transforms = Objects.requireNonNull(transforms);
        this.storage = Objects.requireNonNull(storage);
        this.conflicts = Objects.requireNonNull(conflicts);
        this.authorizer = Objects.requireNonNull(authorizer);
        this.unknownOrigins = Objects.requireNonNull(unknownOrigins);
        this.clock = Objects.requireNonNull(clock);
    }

    public MaterializedSyncService withAuthorization(SyncAuthorizer policy) { return new MaterializedSyncService(storage, conflicts, policy, unknownOrigins, clock, transforms); }
    public MaterializedSyncService withUnknownOrigins(UnknownOrigins policy) { return new MaterializedSyncService(storage, conflicts, authorizer, policy, clock, transforms); }
    public MaterializedSyncService withClock(Clock clock) { return new MaterializedSyncService(storage, conflicts, authorizer, unknownOrigins, clock, transforms); }

    public MaterializedSyncService withTransforms(TransformRegistry registry) { return new MaterializedSyncService(storage, conflicts, authorizer, unknownOrigins, clock, registry); }

    public IngestionCheckpoint checkpoint(String connector, MappingConfig mapping, String partition, RequestContext context) {
        requirePipeline(context, connector, mapping);
        var checkpoint = storage.getIngestionCheckpoint(context, checkpointKey(connector, mapping, partition));
        if (checkpoint != null && !checkpoint.configuration().equals(configuration(mapping))) throw new IllegalStateException("Ingestion configuration changed; use an explicitly migrated pipeline");
        return checkpoint;
    }

    public SyncResult sync(Connector connector, SourceQuery query, MappingConfig mapping, RequestContext context) {
        requirePipeline(context, connector.name(), mapping);
        var binding = Objects.requireNonNull(storage.schemaBinding(), "Apply the schema before ingestion");
        storage.requireSchemaBinding(context, binding);
        MappingSchemaValidator.validate(mapping, binding.schema());
        if (!mapping.links().isEmpty() && !storage.capabilities().relationshipAssertions()) throw new UnsupportedOperationException("Relationship sync requires transactional membership provenance");
        var definition = binding.schema().objectTypes().stream().filter(type -> type.name().equals(mapping.objectType())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown mapping object type"));
        var fields = new LinkedHashMap<String, PropertyDefinition>();
        definition.properties().forEach(field -> fields.put(field.name(), field));
        for (String field : mapping.properties().keySet()) {
            if (!fields.containsKey(field) || fields.get(field).readOnly()) throw new IllegalArgumentException("Unknown or managed mapping target: " + field);
        }
        var governedFields = fields.values().stream().filter(field -> !field.primary() && !field.readOnly()).map(PropertyDefinition::name)
                .collect(java.util.stream.Collectors.toSet());
        governedFields.add("_entity");
        if (!mapping.links().isEmpty()) {
            if (fields.containsKey("links") && conflicts.configuredFields().contains("links")) throw new IllegalArgumentException("links conflict rule is ambiguous with a stored property; use relationship-type rules");
            governedFields.add("links");
            for (var link : mapping.links()) {
                governedFields.add("links." + link.linkType());
                binding.schema().linkTypes().stream().filter(type -> type.name().equals(link.linkType())).findFirst().orElseThrow().properties().stream()
                        .filter(field -> !field.primary() && !field.readOnly()).forEach(field -> governedFields.add("links." + link.linkType() + "." + field.name()));
            }
        }
        if (!governedFields.containsAll(conflicts.configuredFields())) throw new IllegalArgumentException("Conflict policy names an unknown or managed field");
        String runId = "sync_" + UUID.randomUUID();
        var mapper = new RecordMapper(mapping, transforms);
        String configuration = configuration(mapper.fingerprint());
        var counts = new Counts();
        var failures = new ArrayList<SyncFailure>();
        try (var records = connector.read(query)) {
            var iterator = records.iterator();
            while (iterator.hasNext()) {
                var source = iterator.next();
                try {
                    var mapped = mapper.map(source);
                    var values = normalize(binding.schema(), fields, mapped);
                    var links = RelationshipSync.normalize(binding.schema(), mapped.links());
                    var origin = origin(connector.name(), mapper.fingerprint(), source, runId, binding.id());
                    var outcome = apply(context, connector.name(), mapping, source, mapped.key(), values, links, origin, configuration, fields, binding);
                    counts.add(outcome);
                } catch (RuntimeException failure) {
                    failures.add(new SyncFailure(source.sourceSystem(), source.sourceRecordId(), failure.getMessage()));
                    break; // Never acknowledge a later cursor over a failed record.
                }
            }
        }
        return new SyncResult(counts.created, counts.updated, counts.deleted, counts.restored, counts.observed, counts.ignored, counts.replayed, counts.conflicts, failures, counts.relationshipChanges);
    }

    private Outcome apply(RequestContext context, String connector, MappingConfig mapping, SourceRecord source, EntityKey key,
                          Map<String, Object> values, List<MappedLink> links, MutationSource origin, String configuration,
                          Map<String, PropertyDefinition> fields, SchemaBinding binding) {
        String receiptKey = receiptKey(connector, mapping, source);
        String checkpointKey = source.position() == null ? null : checkpointKey(connector, mapping, source.position().partition());
        String fingerprint = fingerprint(source, key, values, origin.producedAt(), configuration);
        if (!mapping.links().isEmpty()) fingerprint = LineageValues.hash(true, List.of("relationship-ingestion-v1", fingerprint, RelationshipSync.input(links)));
        for (int attempt = 0; attempt < 8; attempt++) {
            try (var tx = storage.beginTransaction(context, binding)) {
                tx.acquireWrite();
                requireTarget(context, connector, mapping, key, tx);
                var checkpoint = checkpointKey == null ? null : tx.getIngestionCheckpoint(checkpointKey);
                if (checkpoint != null && (!checkpoint.configuration().equals(configuration) || !checkpoint.sourceSystem().equals(source.sourceSystem()))) {
                    throw new IllegalStateException("Ingestion checkpoint belongs to a different source or configuration");
                }
                var receipt = receiptKey == null ? null : tx.getIngestionReceipt(receiptKey);
                if (receipt != null) {
                    if (!receipt.requestHash().equals(fingerprint) || !receipt.target().equals(key)) throw new IllegalArgumentException("Source event identity was reused with different content or configuration");
                    verifyReceipt(receipt, tx.getObject(key.type(), key.id()), !mapping.links().isEmpty());
                    if (!mapping.links().isEmpty()) {
                        RelationshipSync.verifyEvidence(receipt.result().get("relationships"), receipt.result().get("relationshipDigest"), binding.schema(), mapping, key,
                                context, connector, authorizer, tx);
                    }
                    if (source.position() != null && (checkpoint == null || checkpoint.sequence() < source.position().sequence())) throw new IllegalStateException("Receipt and checkpoint disagree");
                    requireTarget(context, connector, mapping, key, tx);
                    return new Outcome("REPLAYED", Map.of());
                }
                if (checkpoint != null && source.position().sequence() <= checkpoint.sequence()) throw new IllegalArgumentException("Unrecognized out-of-order source event");
                tx.mutationSource(origin);
                var existing = tx.getObject(key.type(), key.id());
                var lineage = tx.latestLineage(key);
                var relationships = mapping.links().isEmpty() ? null : new RelationshipSync(binding.schema(), mapping, connector, source, key,
                        links, origin, conflicts, unknownOrigins, authorizer, context, tx);
                boolean blockedDelete = source.operation().equals("DELETE") && relationships != null && relationships.rejected();
                var outcome = blockedDelete ? new Outcome("IGNORED", Map.of()) : applyValues(tx, key, source.operation(), values, existing, lineage, origin, fields);
                requireTarget(context, connector, mapping, key, tx);
                var current = tx.getObject(key.type(), key.id());
                if (relationships != null) {
                    boolean enabled = !blockedDelete && (source.operation().equals("DELETE") ? current == null || current.isDeleted() : current != null && !current.isDeleted());
                    relationships.apply(enabled);
                    relationships.reauthorize();
                    var decisions = new LinkedHashMap<>(outcome.decisions());
                    decisions.putAll(relationships.decisions());
                    String operation = outcome.operation().equals("IGNORED") && relationships.observed() ? "OBSERVED" : outcome.operation();
                    outcome = new Outcome(operation, decisions, relationships.changes());
                }
                if (receiptKey != null) {
                    var result = new LinkedHashMap<String, Object>();
                    result.put("format", relationships == null ? 1 : 2);
                    result.put("operation", outcome.operation());
                    result.put("entityVersion", current == null ? 0L : current.version());
                    result.put("factHash", current == null ? null : factHash(current));
                    if (relationships != null) {
                        var evidence = relationships.evidence();
                        result.put("relationships", evidence);
                        result.put("relationshipDigest", LineageValues.hash(true, evidence));
                    }
                    tx.putIngestionReceipt(new IngestionReceipt(receiptKey, fingerprint, key, result));
                }
                if (source.position() != null) {
                    tx.putIngestionCheckpoint(new IngestionCheckpoint(checkpointKey, checkpoint == null ? 1 : Math.incrementExact(checkpoint.version()),
                            source.position().sequence(), source.position().checkpoint(), source.sourceSystem(), configuration), checkpoint == null ? 0 : checkpoint.version());
                }
                if (receiptKey != null || !outcome.operation().equals("IGNORED") || outcome.conflicts() > 0 || !outcome.relationshipChanges().isEmpty()) {
                    var detail = new LinkedHashMap<String, Object>();
                    detail.put("connector", connector);
                    detail.put("sourceSystem", source.sourceSystem());
                    detail.put("sourceRecordId", source.sourceRecordId());
                    detail.put("runId", origin.operationId());
                    detail.put("operation", outcome.operation());
                    detail.put("decisions", outcome.decisions());
                    if (!mapping.links().isEmpty()) detail.put("relationshipChanges", outcome.relationshipChanges());
                    if (receiptKey != null) detail.put("receiptKey", receiptKey);
                    Instant now = clock.instant();
                    String id = UUID.randomUUID().toString();
                    tx.appendAudit(new AuditEntry("sync_audit_" + id, now, context.tenantId(), context.actorId(), "sync", null, null,
                            key.type() + "/" + key.id(), tx.transactionId(), "SUCCESS", detail));
                    String topic = switch (outcome.operation()) {
                        case "IGNORED" -> "openfoundry.sync.ignored";
                        case "OBSERVED" -> "openfoundry.sync.observed";
                        default -> "openfoundry.sync.applied";
                    };
                    if (!outcome.relationshipChanges().isEmpty()) topic = "openfoundry.sync.applied";
                    tx.enqueueOutbox(new OutboxEntry("sync_event_" + id, context.tenantId(), topic,
                            key.type() + "/" + key.id(), now, tx.transactionId(), detail));
                    for (var decision : outcome.decisions().entrySet()) {
                        tx.enqueueOutbox(new OutboxEntry("sync_conflict_" + UUID.randomUUID(), context.tenantId(), "openfoundry.sync.conflict",
                                key.type() + "/" + key.id(), now, tx.transactionId(), Map.of("connector", connector, "sourceSystem", source.sourceSystem(),
                                "sourceRecordId", source.sourceRecordId(), "field", decision.getKey(), "decision", decision.getValue())));
                    }
                }
                requireTarget(context, connector, mapping, key, tx);
                if (relationships != null) relationships.reauthorize();
                tx.commit();
                return outcome;
            } catch (TransactionConflictException conflict) {
                if (attempt == 7) throw conflict;
            }
        }
        throw new IllegalStateException("Ingestion retry limit reached");
    }

    private Outcome applyValues(Transaction tx, EntityKey key, String operation, Map<String, Object> values, ObjectRecord current,
                                Map<String, FieldProvenance> lineage, MutationSource origin, Map<String, PropertyDefinition> fields) {
        boolean deleting = operation.equals("DELETE");
        boolean alive = current != null && !current.isDeleted();
        var proposals = new LinkedHashMap<String, ConflictResolver.IncomingValue>();
        proposals.put("_entity", incoming(!deleting, origin, !deleting));
        if (deleting) {
            if (current != null) for (String field : current.properties().keySet()) {
                if (fields.containsKey(field) && !fields.get(field).primary() && !fields.get(field).readOnly()) proposals.put(field, incoming(null, origin, false));
            }
        } else if (current != null && current.isDeleted()) {
            // Restoration retains omitted fields; authorize every assertion being made visible again.
            for (var entry : current.properties().entrySet()) if (fields.containsKey(entry.getKey()) && !fields.get(entry.getKey()).primary() && !fields.get(entry.getKey()).readOnly()) {
                proposals.put(entry.getKey(), incoming(entry.getValue(), origin, true));
            }
            values.forEach((field, value) -> proposals.put(field, incoming(value, origin, true)));
        } else values.forEach((field, value) -> proposals.put(field, incoming(value, origin, true)));

        var prior = new LinkedHashMap<String, ConflictResolver.ExistingValue>();
        var retainedUnknown = new HashSet<String>();
        for (var entry : proposals.entrySet()) {
            String field = entry.getKey();
            boolean present = alive && (field.equals("_entity") || current.properties().containsKey(field));
            Object value = field.equals("_entity") ? true : current == null ? null : current.properties().get(field);
            var evidence = lineage.get(field);
            boolean known = evidence != null && evidence.valuePresent() == present && evidence.valueHash().equals(LineageValues.hash(present, value));
            if (known) {
                var owner = evidence.source();
                prior.put(field, new ConflictResolver.ExistingValue(value, label(owner), owner.producedAt(), owner.kind() == MutationSource.Kind.ACTION, present));
            } else if (present || evidence != null || current != null && (field.equals("_entity") || current.isDeleted() && current.properties().containsKey(field))) {
                if (unknownOrigins == UnknownOrigins.REJECT_CHANGES) {
                    var proposed = entry.getValue();
                    if (!LineageValues.hash(present, value).equals(LineageValues.hash(proposed.present(), proposed.value()))) {
                        throw new IllegalStateException("Existing field origin is unknown or inconsistent: " + field);
                    }
                    retainedUnknown.add(field);
                } else {
                    prior.put(field, new ConflictResolver.ExistingValue(value, null, current == null ? null : current.updatedAt(), false, present));
                }
            }
        }
        retainedUnknown.forEach(proposals::remove);
        var resolution = conflicts.resolve(proposals, prior);
        if ((deleting || !alive) && !resolution.accepted().keySet().containsAll(proposals.keySet())) return outcome("IGNORED", resolution);
        if (deleting && alive) {
            tx.deleteObject(key.type(), key.id(), current.version());
            return outcome("DELETED", resolution);
        }
        if (!deleting && current == null) {
            tx.createObject(key.type(), key.id(), values);
            return outcome("CREATED", resolution);
        }
        if (!deleting && current.isDeleted()) {
            var patch = new LinkedHashMap<>(values);
            patch.entrySet().removeIf(entry -> equal(true, entry.getValue(), current.properties().containsKey(entry.getKey()), current.properties().get(entry.getKey())));
            tx.restoreObject(key.type(), key.id(), patch, current.version());
            return outcome("RESTORED", resolution);
        }
        var patch = new LinkedHashMap<String, Object>();
        if (!deleting) resolution.accepted().forEach((field, value) -> {
            if (!field.equals("_entity") && !equal(true, value, current.properties().containsKey(field), current.properties().get(field))) patch.put(field, value);
        });
        long version = current == null ? 0 : current.version();
        if (!patch.isEmpty()) version = tx.updateObject(key.type(), key.id(), patch, version).version();
        var observed = new HashSet<String>();
        for (String field : resolution.provenanceFields()) {
            if (patch.containsKey(field)) continue;
            var previous = lineage.get(field);
            if (previous == null || !sameObservation(previous.source(), origin)) observed.add(field);
        }
        if (!observed.isEmpty()) tx.recordProvenance(key, version, observed);
        String result = !patch.isEmpty() ? "UPDATED" : !observed.isEmpty() ? "OBSERVED" : "IGNORED";
        return outcome(result, resolution);
    }

    private static Map<String, Object> normalize(OntologySchema schema, Map<String, PropertyDefinition> fields, MappedRecord mapped) {
        var result = new LinkedHashMap<String, Object>();
        mapped.properties().forEach((name, value) -> {
            var field = fields.get(name);
            if (field == null || field.readOnly()) throw new IllegalArgumentException("Unknown or managed source field");
            if (field.primary()) {
                if (!RecordMapper.canonicalId(value).equals(mapped.key().id())) throw new IllegalArgumentException("Mapped primary identity disagrees with its key");
            } else if (!mapped.operation().equals("DELETE")) {
                if (value == null && field.required()) throw new PropertyValidationException("REQUIRED_PROPERTY", name);
                result.put(name, value == null ? null : PropertyValues.normalize(schema, field.type(), value, name));
            }
        });
        return PropertyValues.immutableMap(result);
    }

    private MutationSource origin(String connector, String mappingVersion, SourceRecord record, String runId, String binding) {
        var details = new LinkedHashMap<String, Object>();
        details.put("connector", connector);
        details.put("sourcePointer", record.sourceRecordId());
        details.put("mappingVersion", mappingVersion);
        details.put("schemaBinding", binding);
        details.put("observedAt", record.observedAt().toString());
        if (record.provenance() != null) {
            details.put("sourceVersion", record.provenance().sourceVersion());
            details.put("transformation", record.provenance().transformation());
            details.put("producedBy", record.provenance().producedBy());
        }
        if (record.position() != null) {
            details.put("partition", record.position().partition());
            details.put("eventId", record.position().eventId());
            details.put("sequence", record.position().sequence());
        }
        Instant producedAt = record.provenance() == null ? record.observedAt() : record.provenance().producedAt();
        return new MutationSource(MutationSource.Kind.SYNC, record.sourceSystem(), runId, producedAt, details);
    }

    private void requirePipeline(RequestContext context, String connector, MappingConfig mapping) {
        if (context.actorId() == null || !authorizer.allowed(context, connector, mapping, null, null)) throw new SecurityException("Sync pipeline denied");
        if (!storage.capabilities().transactionalLineage() || !storage.capabilities().transactionalIngestion()) throw new UnsupportedOperationException("Sync requires transactional lineage and ingestion metadata");
    }

    private void requireTarget(RequestContext context, String connector, MappingConfig mapping, EntityKey key, Transaction tx) {
        if (!authorizer.allowed(context, connector, mapping, key, tx)) throw new SecurityException("Sync target denied");
    }

    private String configuration(MappingConfig mapping) {
        return configuration(new RecordMapper(mapping, transforms).fingerprint());
    }

    private String configuration(String mappingVersion) {
        return LineageValues.hash(true, Map.of("format", "materialized-sync-v1", "mapping", mappingVersion, "conflicts", conflicts.configuration(), "unknownOrigins", unknownOrigins.name()));
    }

    private static String checkpointKey(String connector, MappingConfig mapping, String partition) {
        if (partition == null || partition.isBlank()) throw new IllegalArgumentException("Checkpoint partition is required");
        return LineageValues.hash(true, List.of("sync-partition-v1", connector, mapping.objectType(), partition));
    }

    private static String receiptKey(String connector, MappingConfig mapping, SourceRecord source) {
        if (source.position() != null) return LineageValues.hash(true, List.of("sync-event-v1", connector, mapping.objectType(), source.position().partition(), source.position().eventId()));
        String version = source.provenance() == null ? null : source.provenance().sourceVersion();
        return version == null || version.isBlank() ? null : LineageValues.hash(true, List.of("sync-version-v1", connector, mapping.objectType(), source.sourceSystem(), source.sourceRecordId(), version));
    }

    private static String fingerprint(SourceRecord source, EntityKey key, Map<String, Object> values, Instant producedAt, String configuration) {
        Object position = source.position() == null ? null : Map.of("partition", source.position().partition(), "eventId", source.position().eventId(),
                "sequence", source.position().sequence(), "checkpoint", source.position().checkpoint());
        Object reported = source.provenance() == null ? null : Arrays.asList(source.provenance().sourceVersion(),
                source.provenance().transformation(), source.provenance().producedBy(), source.provenance().valueHash());
        return LineageValues.hash(true, Arrays.asList(configuration, source.sourceSystem(), source.sourceRecordId(), key.type(), key.id(),
                source.operation(), values, producedAt.toString(), position, reported));
    }

    private static void verifyReceipt(IngestionReceipt receipt, ObjectRecord current, boolean relationships) {
        var result = receipt.result();
        if (!(result.get("format") instanceof Integer || result.get("format") instanceof Long)) throw new IllegalStateException("Invalid source receipt format");
        long format = ((Number) result.get("format")).longValue();
        if (format != (relationships ? 2 : 1)) throw new IllegalStateException("Receipt format disagrees with relationship mapping");
        var keys = format == 1 ? Set.of("format", "operation", "entityVersion", "factHash")
                : Set.of("format", "operation", "entityVersion", "factHash", "relationships", "relationshipDigest");
        if (!result.keySet().equals(keys) || format != 1 && format != 2
                || !(result.get("entityVersion") instanceof Integer || result.get("entityVersion") instanceof Long) || ((Number) result.get("entityVersion")).longValue() < 0
                || !Set.of("CREATED", "UPDATED", "DELETED", "RESTORED", "OBSERVED", "IGNORED").contains(result.get("operation"))) {
            throw new IllegalStateException("Invalid source receipt");
        }
        long version = ((Number) result.get("entityVersion")).longValue();
        if (version == 0 && result.get("factHash") != null || version > 0 && (!(result.get("factHash") instanceof String hash) || !hash.matches("[0-9a-f]{64}"))) {
            throw new IllegalStateException("Invalid receipt fact digest");
        }
        if (version > 0 && (current == null || current.version() < version
                || current.version() == version && !factHash(current).equals(result.get("factHash")))) {
            throw new IllegalStateException("Source receipt and materialized facts disagree");
        }
    }

    private static String factHash(ObjectRecord object) { return LineageValues.hash(true, List.of(object.isDeleted(), object.properties())); }
    private static boolean equal(boolean aPresent, Object a, boolean bPresent, Object b) { return LineageValues.hash(aPresent, a).equals(LineageValues.hash(bPresent, b)); }
    private static ConflictResolver.IncomingValue incoming(Object value, MutationSource source, boolean present) {
        return new ConflictResolver.IncomingValue(value, source.name(), source.producedAt(), false, present);
    }
    private static String label(MutationSource source) {
        return switch (source.kind()) {
            case ACTION -> "action:" + source.name();
            case FUNCTION -> "function:" + source.name();
            case DIRECT, SYNC -> source.name();
        };
    }
    private static boolean sameObservation(MutationSource a, MutationSource b) {
        return a.kind() == b.kind() && a.name().equals(b.name()) && a.producedAt().equals(b.producedAt()) && PropertyValues.canonical(a.details()).equals(PropertyValues.canonical(b.details()));
    }

    private static Outcome outcome(String operation, ConflictResolver.Resolution resolution) {
        var decisions = new LinkedHashMap<String, Object>();
        for (String field : resolution.conflicts()) {
            boolean accepted = resolution.accepted().containsKey(field);
            decisions.put(field, Map.of("policyAccepted", accepted, "applied", accepted && !operation.equals("IGNORED"), "reason", resolution.reasons().get(field)));
        }
        return new Outcome(operation, PropertyValues.immutableMap(decisions));
    }
    private record Outcome(String operation, Map<String, Object> decisions, Map<String, Integer> relationshipChanges) {
        Outcome(String operation, Map<String, Object> decisions) { this(operation, decisions, Map.of()); }
        int conflicts() { return decisions.size(); }
    }
    public record SyncFailure(String sourceSystem, String sourceRecordId, String reason) {}
    public record SyncResult(int created, int updated, int deleted, int restored, int observed, int ignored, int replayed, int conflicts, List<SyncFailure> failures,
                             Map<String, Integer> relationshipChanges) {
        public SyncResult(int created, int updated, int deleted, int restored, int observed, int ignored, int replayed, int conflicts, List<SyncFailure> failures) {
            this(created, updated, deleted, restored, observed, ignored, replayed, conflicts, failures, Map.of());
        }
        public SyncResult(int created, int updated, int deleted, List<SyncFailure> failures) { this(created, updated, deleted, 0, 0, 0, 0, 0, failures); }
        public SyncResult { failures = List.copyOf(failures); relationshipChanges = Map.copyOf(relationshipChanges); }
    }
    private static final class Counts {
        int created, updated, deleted, restored, observed, ignored, replayed, conflicts;
        final Map<String, Integer> relationshipChanges = new LinkedHashMap<>();
        void add(Outcome result) {
            conflicts += result.conflicts();
            result.relationshipChanges().forEach((name, count) -> relationshipChanges.merge(name, count, Integer::sum));
            switch (result.operation()) {
                case "CREATED" -> created++;
                case "UPDATED" -> updated++;
                case "DELETED" -> deleted++;
                case "RESTORED" -> restored++;
                case "OBSERVED" -> observed++;
                case "IGNORED" -> ignored++;
                case "REPLAYED" -> replayed++;
                default -> throw new IllegalStateException("Unknown ingestion outcome");
            }
        }
    }
}
