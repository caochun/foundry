package org.openfoundry.foundation.sync;

import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.*;

import java.time.Instant;
import java.util.*;

/** Plans relationship membership before writes, then applies one source assertion in its enclosing ingestion transaction. */
final class RelationshipSync {
    private final OntologySchema schema;
    private final MappingConfig mapping;
    private final String connector;
    private final SourceRecord sourceRecord;
    private final EntityKey from;
    private final MutationSource origin;
    private final ConflictResolver conflicts;
    private final MaterializedSyncService.UnknownOrigins unknown;
    private final SyncAuthorizer authorizer;
    private final RequestContext context;
    private final Transaction tx;
    private final List<Group> groups = new ArrayList<>();
    private final Map<String, Object> decisions = new LinkedHashMap<>();
    private final Map<String, Integer> changes = new LinkedHashMap<>();
    private boolean observed;

    RelationshipSync(OntologySchema schema, MappingConfig mapping, String connector, SourceRecord sourceRecord, EntityKey from,
                     List<MappedLink> requested, MutationSource origin, ConflictResolver conflicts,
                     MaterializedSyncService.UnknownOrigins unknown, SyncAuthorizer authorizer, RequestContext context, Transaction tx) {
        this.schema = schema; this.mapping = mapping; this.connector = connector; this.sourceRecord = sourceRecord; this.from = from;
        this.origin = origin; this.conflicts = conflicts; this.unknown = unknown; this.authorizer = authorizer; this.context = context; this.tx = tx;
        var byType = new LinkedHashMap<String, List<LinkMapping>>();
        mapping.links().forEach(link -> byType.computeIfAbsent(link.linkType(), ignored -> new ArrayList<>()).add(link));
        var inputs = new LinkedHashMap<String, MappedLink>();
        requested.forEach(link -> inputs.put(link.name(), link));
        boolean deleting = sourceRecord.operation().equals("DELETE");
        for (var entry : byType.entrySet()) {
            if (!deleting && entry.getValue().stream().noneMatch(link -> inputs.containsKey(link.name()))) continue;
            groups.add(plan(entry.getKey(), entry.getValue(), inputs, deleting));
        }
    }

    boolean rejected() { return groups.stream().anyMatch(group -> !group.accepted); }
    Map<String, Object> decisions() { return PropertyValues.immutableMap(decisions); }
    Map<String, Integer> changes() { return Map.copyOf(changes); }
    boolean observed() { return observed; }

    private Group plan(String type, List<LinkMapping> declarations, Map<String, MappedLink> inputs, boolean deleting) {
        var definition = schema.linkTypes().stream().filter(link -> link.name().equals(type)).findFirst().orElseThrow();
        boolean single = definition.cardinality() == Cardinality.MANY_TO_ONE || definition.cardinality() == Cardinality.ONE_TO_ONE;
        if (single && declarations.size() != 1) throw new IllegalArgumentException("A single-valued relationship requires one mapping slot");
        var history = tx.findLinks(type, from, null, true);
        if (history.size() > 1000) throw new IllegalArgumentException("Relationship reconciliation exceeds 1000 identities per scope");
        var current = history.stream().filter(link -> !link.isDeleted()).toList();
        var byId = new LinkedHashMap<String, LinkRecord>();
        history.forEach(link -> byId.put(link.id(), link));
        var group = new Group(new RelationshipScope(from, type, StorageProvider.Direction.OUTBOUND), definition, current);
        current.forEach(link -> group.evidence.put(link.id(), link));
        for (var declaration : declarations) {
            var input = deleting ? new MappedLink(declaration.name(), type, declaration.toType(), null, Map.of()) : inputs.get(declaration.name());
            if (input == null) continue;
            if (input.target() != null) {
                var target = tx.getObject(input.target().type(), input.target().id());
                if (target == null || target.isDeleted()) throw new IllegalArgumentException("Mapped relationship target is unavailable");
                group.targets.add(input.target());
            }
            var owned = single ? current : current.stream().filter(link -> managedId(declaration, link.to()).equals(link.id())).toList();
            LinkRecord existing = null;
            String id = input.target() == null ? null : managedId(declaration, input.target());
            if (input.target() != null) {
                if (single) existing = current.stream().filter(link -> link.to().equals(input.target())).findFirst().orElse(null);
                if (existing != null) id = existing.id();
                else existing = byId.get(id);
                if (existing != null) group.evidence.put(existing.id(), existing);
                group.writes.add(new Write(id, input.target(), input.properties(), existing));
            }
            String retained = id;
            owned.stream().filter(link -> !link.id().equals(retained)).forEach(link -> group.remove.put(link.id(), link));
        }
        authorize(group);
        var desiredIds = new TreeSet<String>();
        current.forEach(link -> desiredIds.add(link.id()));
        desiredIds.removeAll(group.remove.keySet());
        group.writes.forEach(write -> desiredIds.add(write.id));
        var currentIds = current.stream().map(LinkRecord::id).sorted().toList();
        var assertion = tx.relationshipAssertion(group.scope);
        var prior = new LinkedHashMap<String, ConflictResolver.ExistingValue>();
        String policy = policy(type, null);
        if (assertion != null) prior.put(policy, existing(currentIds, assertion.source(), true));
        else if (!history.isEmpty()) {
            if (unknown == MaterializedSyncService.UnknownOrigins.REJECT_CHANGES) {
                if (!currentIds.equals(List.copyOf(desiredIds))) throw new IllegalStateException("Relationship membership origin is unknown");
                group.observe = false;
            } else {
                Instant latest = history.stream().map(LinkRecord::updatedAt).max(Comparator.naturalOrder()).orElseThrow();
                prior.put(policy, new ConflictResolver.ExistingValue(currentIds, null, latest, false));
            }
        }
        var membership = conflicts.resolve(Map.of(policy, incoming(List.copyOf(desiredIds), true)), prior);
        group.accepted = membership.accepted().containsKey(policy);
        group.observe = group.observe && membership.provenanceFields().contains(policy);
        addDecisions(type, "membership", membership, group.accepted);
        for (var link : group.remove.values()) {
            var result = fields(group, link, Map.of(), true, false);
            if (!result.accepted().keySet().containsAll(result.reasons().keySet())) group.accepted = false;
        }
        for (var write : group.writes) {
            if (write.existing == null) continue;
            write.resolution = fields(group, write.existing, write.values, false, write.existing.isDeleted());
            if (write.existing.isDeleted() && !write.resolution.accepted().keySet().containsAll(write.resolution.reasons().keySet())) group.accepted = false;
        }
        if (definition.cardinality() == Cardinality.ONE_TO_ONE || definition.cardinality() == Cardinality.ONE_TO_MANY) {
            var affectedTargets = new HashSet<EntityKey>(group.targets);
            group.remove.values().forEach(link -> affectedTargets.add(link.to()));
            for (var target : affectedTargets) {
                var incomingHistory = tx.findLinks(type, null, target, true);
                var active = incomingHistory.stream().filter(link -> !link.isDeleted()).toList();
                if (group.writes.stream().anyMatch(write -> write.target.equals(target)) && active.stream().anyMatch(link -> !link.from().equals(from))) {
                    throw new IllegalStateException("Mapped target has a conflicting relationship");
                }
                var scope = new RelationshipScope(target, type, StorageProvider.Direction.INBOUND);
                var currentState = tx.relationshipAssertion(scope);
                var oldIds = active.stream().map(LinkRecord::id).sorted().toList();
                var nextIds = new TreeSet<>(oldIds);
                group.remove.values().stream().filter(link -> link.to().equals(target)).forEach(link -> nextIds.remove(link.id()));
                group.writes.stream().filter(write -> write.target.equals(target)).forEach(write -> nextIds.add(write.id));
                var previous = new LinkedHashMap<String, ConflictResolver.ExistingValue>();
                boolean observe = true;
                if (currentState != null) previous.put(policy, existing(oldIds, currentState.source(), true));
                else if (!incomingHistory.isEmpty()) {
                    if (unknown == MaterializedSyncService.UnknownOrigins.REJECT_CHANGES) {
                        if (!oldIds.equals(List.copyOf(nextIds))) throw new IllegalStateException("Incoming relationship membership origin is unknown");
                        observe = false;
                    } else previous.put(policy, new ConflictResolver.ExistingValue(oldIds, null,
                            incomingHistory.stream().map(LinkRecord::updatedAt).max(Comparator.naturalOrder()).orElseThrow(), false));
                }
                var resolution = conflicts.resolve(Map.of(policy, incoming(List.copyOf(nextIds), true)), previous);
                boolean accepted = resolution.accepted().containsKey(policy);
                group.accepted &= accepted;
                group.inverseScopes.put(scope, observe && resolution.provenanceFields().contains(policy));
                addDecisions(type, "incoming." + target.id(), resolution, accepted);
            }
        }
        if (!group.accepted) {
            String prefix = "links." + type + ".";
            decisions.replaceAll((key, value) -> key.startsWith(prefix) ? applied(value, false) : value);
        }
        return group;
    }

    private ConflictResolver.Resolution fields(Group group, LinkRecord link, Map<String, Object> values, boolean deleting, boolean restoring) {
        var proposals = new LinkedHashMap<String, ConflictResolver.IncomingValue>();
        if (deleting || restoring) proposals.put("_entity", incoming(!deleting, !deleting));
        if (deleting || restoring) {
            for (var property : group.definition.properties()) if (!property.primary() && !property.readOnly() && link.properties().containsKey(property.name())) {
                proposals.put(property.name(), incoming(deleting ? null : link.properties().get(property.name()), !deleting));
            }
        }
        if (!deleting) values.forEach((field, value) -> proposals.put(field, incoming(value, true)));
        var lineage = tx.latestLineage(new EntityKey(link.type(), link.id()));
        var accepted = new LinkedHashMap<String, Object>();
        var reasons = new LinkedHashMap<String, String>();
        var owners = new HashSet<String>();
        var conflictFields = new HashSet<String>();
        for (var entry : proposals.entrySet()) {
            String field = entry.getKey();
            boolean present = !link.isDeleted() && (field.equals("_entity") || link.properties().containsKey(field));
            Object value = field.equals("_entity") ? true : link.properties().get(field);
            var evidence = lineage.get(field);
            ConflictResolver.ExistingValue before = null;
            if (evidence != null && evidence.valueHash().equals(LineageValues.hash(present, value)) && evidence.valuePresent() == present) {
                before = existing(value, evidence.source(), present);
            } else if (present || evidence != null || field.equals("_entity") || link.isDeleted() && link.properties().containsKey(field)) {
                if (unknown == MaterializedSyncService.UnknownOrigins.REJECT_CHANGES) {
                    if (!LineageValues.hash(present, value).equals(LineageValues.hash(entry.getValue().present(), entry.getValue().value()))) {
                        throw new IllegalStateException("Relationship field origin is unknown: " + field);
                    }
                    // Equal legacy values do not transfer ownership.
                    accepted.put(field, entry.getValue().value());
                    reasons.put(field, "Unknown origin retained");
                    continue;
                }
                before = new ConflictResolver.ExistingValue(value, null, link.updatedAt(), false, present);
            }
            String rule = policy(group.definition.name(), field.equals("_entity") ? null : field);
            var result = conflicts.resolve(Map.of(rule, entry.getValue()), before == null ? Map.of() : Map.of(rule, before));
            if (result.accepted().containsKey(rule)) accepted.put(field, result.accepted().get(rule));
            if (result.provenanceFields().contains(rule)) owners.add(field);
            reasons.put(field, result.reasons().get(rule));
            if (!result.conflicts().isEmpty()) {
                conflictFields.add(field);
                decisions.put("links." + link.type() + "." + link.id() + "." + field,
                        Map.of("policyAccepted", result.accepted().containsKey(rule), "applied", result.accepted().containsKey(rule), "reason", result.reasons().get(rule)));
            }
        }
        return new ConflictResolver.Resolution(accepted, reasons, owners, conflictFields);
    }

    void apply(boolean enabled) {
        for (var group : groups) {
            if (!enabled || !group.accepted) {
                String prefix = "links." + group.definition.name() + ".";
                decisions.replaceAll((key, value) -> key.startsWith(prefix) ? applied(value, false) : value);
                continue;
            }
            for (var link : group.remove.values()) {
                tx.deleteLink(link.type(), link.id(), link.version());
                increment("deleted");
            }
            for (var write : group.writes) {
                LinkRecord link;
                if (write.existing == null) {
                    link = tx.createLink(group.definition.name(), write.id, from, write.target, write.values);
                    increment("created");
                } else {
                    link = write.existing;
                    if (link.isDeleted()) {
                        link = tx.restoreLink(link.type(), link.id(), link.version());
                        increment("restored");
                    }
                    var patch = new LinkedHashMap<String, Object>();
                    for (var entry : write.resolution.accepted().entrySet()) {
                        if (entry.getKey().equals("_entity")) continue;
                        if (!LineageValues.hash(true, entry.getValue()).equals(LineageValues.hash(link.properties().containsKey(entry.getKey()), link.properties().get(entry.getKey())))) patch.put(entry.getKey(), entry.getValue());
                    }
                    if (!patch.isEmpty()) {
                        link = tx.updateLink(link.type(), link.id(), patch, link.version());
                        increment("updated");
                    }
                    var observations = new HashSet<String>();
                    var latest = tx.latestLineage(new EntityKey(link.type(), link.id()));
                    for (String field : write.resolution.provenanceFields()) {
                        var previous = latest.get(field);
                        if (previous == null || !sameObservation(previous.source(), origin)) observations.add(field);
                    }
                    if (!observations.isEmpty()) {
                        tx.recordProvenance(new EntityKey(link.type(), link.id()), link.version(), observations);
                        observed = true;
                    }
                }
                group.evidence.put(link.id(), link);
            }
            if (group.observe) {
                var latest = tx.relationshipAssertion(group.scope);
                if (latest == null || !sameObservation(latest.source(), origin)) {
                    tx.observeRelationships(group.scope, latest == null ? 0 : latest.revision());
                    observed = true;
                }
            }
            group.inverseScopes.forEach((scope, observe) -> {
                if (observe) {
                    var latest = tx.relationshipAssertion(scope);
                    if (latest == null || !sameObservation(latest.source(), origin)) {
                        tx.observeRelationships(scope, latest == null ? 0 : latest.revision());
                        observed = true;
                    }
                }
            });
        }
    }

    List<Map<String, Object>> evidence() {
        var result = new ArrayList<Map<String, Object>>();
        for (var group : groups) {
            var links = new ArrayList<Map<String, Object>>();
            var targets = new TreeSet<EntityKey>(Comparator.comparing(EntityKey::type).thenComparing(EntityKey::id));
            targets.addAll(group.targets);
            for (var original : group.evidence.values()) {
                var link = tx.getLink(original.type(), original.id());
                if (link == null) throw new IllegalStateException("Relationship evidence disappeared");
                targets.add(link.to());
                links.add(Map.of("id", link.id(), "version", link.version(), "targetType", link.to().type(), "targetId", link.to().id(), "hash", hash(link)));
            }
            var latest = tx.relationshipAssertion(group.scope);
            var inverse = group.inverseScopes.keySet().stream().map(scope -> {
                var assertion = tx.relationshipAssertion(scope);
                return Map.<String, Object>of("type", scope.endpoint().type(), "id", scope.endpoint().id(), "revision", assertion == null ? 0L : assertion.revision());
            }).toList();
            result.add(Map.of("type", group.definition.name(), "scopeRevision", latest == null ? 0L : latest.revision(),
                    "links", links, "targets", targets.stream().map(key -> Map.of("type", key.type(), "id", key.id())).toList(), "incoming", inverse));
        }
        return List.copyOf(result);
    }

    void reauthorize() { groups.forEach(this::authorize); }

    private void authorize(Group group) {
        if (!authorizer.allowed(context, connector, mapping, from, tx)) throw new SecurityException("Relationship source denied");
        for (var link : group.evidence.values()) {
            if (!authorizer.allowedRelationship(context, connector, mapping, new EntityKey(link.type(), link.id()), link.from(), link.to(), tx)) throw new SecurityException("Existing relationship target denied");
        }
        for (var target : group.targets) if (!authorizer.allowed(context, connector, mapping, target, tx)) throw new SecurityException("Mapped relationship target denied");
        for (var write : group.writes) if (!authorizer.allowedRelationship(context, connector, mapping, new EntityKey(group.definition.name(), write.id), from, write.target, tx)) throw new SecurityException("Mapped relationship identity denied");
    }

    static void verifyEvidence(Object raw, Object digest, OntologySchema schema, MappingConfig mapping, EntityKey from,
                               RequestContext context, String connector, SyncAuthorizer authorizer, Transaction tx) {
        if (!(raw instanceof List<?> entries) || !(digest instanceof String hash) || !LineageValues.hash(true, raw).equals(hash)) throw new IllegalStateException("Invalid relationship receipt evidence");
        var types = new HashSet<String>();
        for (Object item : entries) {
            var entry = map(item);
            if (!entry.keySet().equals(Set.of("type", "scopeRevision", "links", "targets", "incoming"))) throw new IllegalStateException("Invalid relationship receipt entry");
            String type = text(entry, "type");
            if (!types.add(type) || mapping.links().stream().noneMatch(link -> link.linkType().equals(type))) throw new IllegalStateException("Unregistered relationship receipt type");
            var definition = schema.linkTypes().stream().filter(link -> link.name().equals(type)).findFirst().orElseThrow();
            if (!definition.fromType().equals(from.type())) throw new IllegalStateException("Relationship receipt source mismatch");
            long revision = integer(entry.get("scopeRevision"));
            var scope = tx.relationshipAssertion(new RelationshipScope(from, type, StorageProvider.Direction.OUTBOUND));
            if (revision > 0 && (scope == null || scope.revision() < revision)) throw new IllegalStateException("Relationship scope evidence is missing");
            if (!(entry.get("links") instanceof List<?> links) || !(entry.get("targets") instanceof List<?> targets)) throw new IllegalStateException("Invalid relationship evidence collections");
            var ids = new HashSet<String>();
            for (Object encoded : links) {
                var row = map(encoded);
                if (!row.keySet().equals(Set.of("id", "version", "targetType", "targetId", "hash"))) throw new IllegalStateException("Invalid relationship identity evidence");
                String id = text(row, "id");
                if (!ids.add(id)) throw new IllegalStateException("Duplicate relationship evidence");
                var link = tx.getLink(type, id);
                long version = integer(row.get("version"));
                if (version < 1 || link == null || !link.from().equals(from) || !link.to().equals(new EntityKey(text(row, "targetType"), text(row, "targetId")))
                        || link.version() < version || link.version() == version && !hash(link).equals(text(row, "hash"))) throw new IllegalStateException("Relationship receipt and facts disagree");
                if (!authorizer.allowedRelationship(context, connector, mapping, new EntityKey(type, id), link.from(), link.to(), tx)) throw new SecurityException("Relationship replay denied");
            }
            if (!(entry.get("incoming") instanceof List<?> incoming)) throw new IllegalStateException("Invalid incoming scope evidence");
            for (Object encoded : incoming) {
                var saved = map(encoded);
                if (!saved.keySet().equals(Set.of("type", "id", "revision")) || !definition.toType().equals(text(saved, "type"))) throw new IllegalStateException("Invalid incoming scope identity");
                var target = new EntityKey(text(saved, "type"), text(saved, "id"));
                var latest = tx.relationshipAssertion(new RelationshipScope(target, type, StorageProvider.Direction.INBOUND));
                long required = integer(saved.get("revision"));
                if (required > 0 && (latest == null || latest.revision() < required)) throw new IllegalStateException("Incoming relationship scope evidence is missing");
                if (!authorizer.allowed(context, connector, mapping, target, tx)) throw new SecurityException("Incoming scope replay denied");
            }
            for (Object encoded : targets) {
                var target = map(encoded);
                if (!target.keySet().equals(Set.of("type", "id")) || !definition.toType().equals(text(target, "type"))) throw new IllegalStateException("Invalid relationship target evidence");
                if (!authorizer.allowed(context, connector, mapping, new EntityKey(text(target, "type"), text(target, "id")), tx)) throw new SecurityException("Relationship target replay denied");
            }
        }
    }

    static List<MappedLink> normalize(OntologySchema schema, List<MappedLink> mapped) {
        return mapped.stream().map(link -> {
            var definition = schema.linkTypes().stream().filter(type -> type.name().equals(link.linkType())).findFirst().orElseThrow();
            var values = new LinkedHashMap<String, Object>();
            link.properties().forEach((name, value) -> {
                var field = definition.properties().stream().filter(property -> property.name().equals(name)).findFirst().orElseThrow();
                if (field.primary() || field.readOnly()) throw new IllegalArgumentException("Mapped relationship identity and managed fields cannot be supplied");
                if (value == null && field.required()) throw new PropertyValidationException("REQUIRED_PROPERTY", name);
                values.put(name, value == null ? null : PropertyValues.normalize(schema, field.type(), value, name));
            });
            return new MappedLink(link.name(), link.linkType(), link.toType(), link.target(), values);
        }).toList();
    }

    static List<Map<String, Object>> input(List<MappedLink> links) {
        return links.stream().map(link -> {
            var value = new LinkedHashMap<String, Object>();
            value.put("name", link.name()); value.put("type", link.linkType()); value.put("targetType", link.toType());
            value.put("targetId", link.target() == null ? null : link.target().id()); value.put("properties", link.properties());
            return Collections.unmodifiableMap(value);
        }).toList();
    }
    private String managedId(LinkMapping slot, EntityKey target) {
        String scope = LineageValues.hash(true, List.of(connector, sourceRecord.sourceSystem(), sourceRecord.sourceRecordId(), from.type(), from.id(), slot.name(), slot.linkType()));
        return "sync_" + scope + "_" + LineageValues.hash(true, List.of(target.type(), target.id()));
    }
    private String policy(String type, String field) {
        var configured = conflicts.configuredFields();
        if (field != null && configured.contains("links." + type + "." + field)) return "links." + type + "." + field;
        return configured.contains("links." + type) ? "links." + type : "links";
    }
    private ConflictResolver.IncomingValue incoming(Object value, boolean present) { return new ConflictResolver.IncomingValue(value, origin.name(), origin.producedAt(), false, present); }
    private static ConflictResolver.ExistingValue existing(Object value, MutationSource source, boolean present) {
        String name = switch (source.kind()) { case ACTION -> "action:" + source.name(); case FUNCTION -> "function:" + source.name(); default -> source.name(); };
        return new ConflictResolver.ExistingValue(value, name, source.producedAt(), source.kind() == MutationSource.Kind.ACTION, present);
    }
    private void addDecisions(String type, String label, ConflictResolver.Resolution result, boolean applied) {
        result.conflicts().forEach(field -> decisions.put("links." + type + "." + label,
                Map.of("policyAccepted", result.accepted().containsKey(field), "applied", applied && result.accepted().containsKey(field), "reason", result.reasons().get(field))));
    }
    private static Object applied(Object value, boolean applied) { var result = new LinkedHashMap<>(map(value)); result.put("applied", applied); return result; }
    private void increment(String operation) { changes.merge(operation, 1, Integer::sum); }
    private static boolean sameObservation(MutationSource a, MutationSource b) { return a.kind() == b.kind() && a.name().equals(b.name()) && a.producedAt().equals(b.producedAt()) && PropertyValues.canonical(a.details()).equals(PropertyValues.canonical(b.details())); }
    private static String hash(LinkRecord link) { return LineageValues.hash(true, List.of(link.from().type(), link.from().id(), link.to().type(), link.to().id(), link.isDeleted(), link.properties())); }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) { if (!(value instanceof Map<?, ?>)) throw new IllegalStateException("Invalid relationship evidence map"); return (Map<String, Object>) value; }
    private static String text(Map<String, Object> map, String name) { if (!(map.get(name) instanceof String value) || value.isBlank()) throw new IllegalStateException("Invalid relationship evidence field"); return value; }
    private static long integer(Object value) { if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() < 0) throw new IllegalStateException("Invalid relationship evidence version"); return ((Number) value).longValue(); }

    private static final class Group {
        final RelationshipScope scope;
        final LinkTypeDefinition definition;
        final Map<String, LinkRecord> evidence = new LinkedHashMap<>();
        final Set<EntityKey> targets = new HashSet<>();
        final Map<String, LinkRecord> remove = new LinkedHashMap<>();
        final List<Write> writes = new ArrayList<>();
        final Map<RelationshipScope, Boolean> inverseScopes = new LinkedHashMap<>();
        boolean accepted = true;
        boolean observe = true;
        Group(RelationshipScope scope, LinkTypeDefinition definition, List<LinkRecord> current) { this.scope = scope; this.definition = definition; }
    }
    private static final class Write {
        final String id;
        final EntityKey target;
        final Map<String, Object> values;
        final LinkRecord existing;
        ConflictResolver.Resolution resolution;
        Write(String id, EntityKey target, Map<String, Object> values, LinkRecord existing) { this.id = id; this.target = target; this.values = values; this.existing = existing; }
    }
}
