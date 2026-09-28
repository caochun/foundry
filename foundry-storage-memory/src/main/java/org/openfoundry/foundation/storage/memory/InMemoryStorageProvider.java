package org.openfoundry.foundation.storage.memory;

import org.openfoundry.foundation.spi.AuditEntry;
import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.EntityOperation;
import org.openfoundry.foundation.spi.HistorySnapshot;
import org.openfoundry.foundation.spi.LinkRecord;
import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.OutboxEntry;
import org.openfoundry.foundation.spi.QueryOptions;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.StorageCapabilities;
import org.openfoundry.foundation.spi.StorageProvider;
import org.openfoundry.foundation.spi.Transaction;
import org.openfoundry.foundation.spi.TemporalHistory;
import java.time.Clock;
import org.openfoundry.foundation.spi.schema.PropertyValues;
import org.openfoundry.foundation.spi.schema.UniquePropertyIndex;
import org.openfoundry.foundation.spi.TraversalResult;
import org.openfoundry.foundation.spi.TraversalStep;
import org.openfoundry.foundation.spi.schema.Cardinality;
import org.openfoundry.foundation.spi.schema.LinkTypeDefinition;
import org.openfoundry.foundation.spi.schema.ObjectTypeDefinition;
import org.openfoundry.foundation.spi.schema.OntologySchema;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Reference StorageProvider used for tests and local development.
 *
 * It intentionally implements the same temporal and transaction semantics as
 * a relational provider, so the conformance suite can run without a database.
 */
public final class InMemoryStorageProvider implements StorageProvider {
    private static final StorageCapabilities CAPABILITIES = new StorageCapabilities(
            true, true, false, false, false, true, false, true);

    private final Object monitor = new Object();
    private final Clock clock;
    private final org.openfoundry.foundation.validation.PropertyValidator propertyValidator = new org.openfoundry.foundation.validation.PropertyValidator();

    public InMemoryStorageProvider() {
        this(Clock.systemUTC());
    }

    public InMemoryStorageProvider(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    private OntologySchema schema;
    private volatile String schemaId;
    private State state = new State();
    private long revision;

    @Override
    public void applySchema(RequestContext context, OntologySchema schema) {
        Objects.requireNonNull(context, "context must not be null");
        propertyValidator.validateSchema(schema);
        synchronized (monitor) {
            if (!Objects.equals(this.schema, schema)) {
                revision++;
                schemaId = java.util.UUID.randomUUID().toString();
            }
            this.schema = Objects.requireNonNull(schema, "schema must not be null");
        }
    }

    @Override
    public org.openfoundry.foundation.spi.SchemaBinding schemaBinding() {
        synchronized (monitor) {
            return schema == null ? null : new org.openfoundry.foundation.spi.SchemaBinding(schemaId, schema);
        }
    }

    @Override
    public void requireSchemaBinding(RequestContext context, org.openfoundry.foundation.spi.SchemaBinding expected) {
        synchronized (monitor) {
            if (expected == null || schemaId == null || !schemaId.equals(expected.id())) {
                throw new org.openfoundry.foundation.spi.SchemaVersionMismatchException();
            }
        }
    }

    @Override
    public Transaction beginTransaction(RequestContext context, org.openfoundry.foundation.spi.SchemaBinding expected) {
        synchronized (monitor) {
            requireSchemaBinding(context, expected);
            return beginTransaction(context);
        }
    }

    @Override
    public ObjectRecord getObject(RequestContext context, String type, String id) {
        synchronized (monitor) {
            return state.objects.get(objectKey(context, type, id));
        }
    }

    @Override
    public List<ObjectRecord> queryObjects(RequestContext context, String type, QueryOptions options) {
        synchronized (monitor) {
            if (options.asOfValidTime() != null) {
                return TemporalHistory.page(state.objects.values().stream()
                        .filter(object -> object.tenantId().equals(context.tenantId()) && object.type().equals(type))
                        .map(object -> {
                            var history = findHistory(context, object.key());
                            var snapshot = TemporalHistory.at(history, options.asOfValidTime(), options.asOfRecordedTime());
                            return snapshot == null ? null : TemporalHistory.object(context.tenantId(), snapshot, TemporalHistory.createdAt(history));
                        }).filter(Objects::nonNull).filter(object -> options.includeDeleted() || !object.isDeleted())
                        .sorted(Comparator.comparing(ObjectRecord::id)).toList(), options);
            }
            return page(state.objects.values().stream()
                    .filter(object -> object.tenantId().equals(context.tenantId()) && object.type().equals(type))
                    .filter(object -> options.includeDeleted() || !object.isDeleted())
                    .sorted(Comparator.comparing(ObjectRecord::id))
                    .toList(), options);
        }
    }

    @Override
    public HistorySnapshot getObjectAtVersion(RequestContext context, String type, String id,
                                              long version) {
        return findHistory(context, new EntityKey(type, id)).stream()
                .filter(snapshot -> snapshot.version() == version)
                .findFirst()
                .orElse(null);
    }

    @Override
    public HistorySnapshot getObjectAtTime(RequestContext context, String type, String id,
                                           Instant validTime, Instant recordedTime) {
        return findAtTime(context, new EntityKey(type, id), validTime, recordedTime);
    }

    @Override
    public LinkRecord getLink(RequestContext context, String type, String id) {
        synchronized (monitor) {
            return state.links.get(linkKey(context, type, id));
        }
    }

    @Override
    public List<LinkRecord> getLinks(RequestContext context, EntityKey endpoint, String linkType,
                                     Direction direction, QueryOptions options) {
        synchronized (monitor) {
            if (options.asOfValidTime() != null) {
                return TemporalHistory.page(state.links.values().stream()
                        .filter(link -> link.tenantId().equals(context.tenantId()) && link.type().equals(linkType))
                        .map(link -> {
                            var history = findHistory(context, new EntityKey(link.type(), link.id()));
                            var snapshot = TemporalHistory.at(history, options.asOfValidTime(), options.asOfRecordedTime());
                            return snapshot == null ? null : TemporalHistory.link(context.tenantId(), snapshot, history, options.asOfRecordedTime());
                        }).filter(Objects::nonNull)
                        .filter(link -> direction == Direction.OUTBOUND ? link.from().equals(endpoint) : link.to().equals(endpoint))
                        .filter(link -> options.includeDeleted() || !link.isDeleted())
                        .sorted(Comparator.comparing(LinkRecord::id)).toList(), options);
            }
            return page(state.links.values().stream()
                    .filter(link -> link.tenantId().equals(context.tenantId()))
                    .filter(link -> link.type().equals(linkType))
                    .filter(link -> direction == Direction.OUTBOUND
                            ? link.from().equals(endpoint) : link.to().equals(endpoint))
                    .filter(link -> options.includeDeleted() || !link.isDeleted())
                    .sorted(Comparator.comparing(LinkRecord::id))
                    .toList(), options);
        }
    }

    @Override
    public HistorySnapshot getLinkAtVersion(RequestContext context, String type, String id,
                                            long version) {
        return findHistory(context, new EntityKey(type, id)).stream()
                .filter(snapshot -> snapshot.version() == version)
                .findFirst()
                .orElse(null);
    }

    @Override
    public HistorySnapshot getLinkAtTime(RequestContext context, String type, String id,
                                         Instant validTime, Instant recordedTime) {
        return findAtTime(context, new EntityKey(type, id), validTime, recordedTime);
    }

    @Override
    public TraversalResult traverseAsOf(RequestContext context, EntityKey start,
                                        List<TraversalStep> path, Instant validTime,
                                        Instant recordedTime, QueryOptions options) {
        synchronized (monitor) {
            var objects = state.objects.values().stream().filter(object -> object.tenantId().equals(context.tenantId()))
                    .flatMap(object -> findHistory(context, object.key()).stream()).toList();
            var links = state.links.values().stream().filter(link -> link.tenantId().equals(context.tenantId()))
                    .flatMap(link -> findHistory(context, new EntityKey(link.type(), link.id())).stream()).toList();
            return TemporalHistory.traverse(objects, links, start, path, validTime, recordedTime, options);
        }
    }

    @Override
    public List<HistorySnapshot> getEntityHistory(RequestContext context, EntityKey key) {
        return findHistory(context, key);
    }

    public List<AuditEntry> auditEntries(RequestContext context) {
        synchronized (monitor) {
            return state.audits.stream().filter(entry -> entry.tenantId().equals(context.tenantId())).toList();
        }
    }

    public List<OutboxEntry> outboxEntries(RequestContext context) {
        synchronized (monitor) {
            return state.outbox.stream().filter(entry -> entry.tenantId().equals(context.tenantId())).toList();
        }
    }

    @Override
    public List<org.openfoundry.foundation.spi.ActionExecution> pendingActions(RequestContext context, Instant now, int limit) {
        if (limit < 1 || limit > 10000) throw new IllegalArgumentException("Invalid continuation limit");
        synchronized (monitor) {
            return state.executions.entrySet().stream()
                    .filter(entry -> entry.getKey().equals(objectKey(context, "__action_execution", entry.getValue().id())))
                    .map(Map.Entry::getValue)
                    .filter(run -> Objects.equals(run.actorId(), context.actorId()) && run.availableAt() != null && !run.availableAt().isAfter(now))
                    .sorted(Comparator.comparing(org.openfoundry.foundation.spi.ActionExecution::availableAt)
                            .thenComparing(org.openfoundry.foundation.spi.ActionExecution::id)).limit(limit).toList();
        }
    }

    @Override
    public Transaction beginTransaction(RequestContext context) {
        Objects.requireNonNull(context, "context must not be null");
        synchronized (monitor) {
            return new MemoryTransaction(context, revision, state.copy());
        }
    }

    @Override
    public StorageCapabilities capabilities() {
        return CAPABILITIES;
    }

    private List<HistorySnapshot> findHistory(RequestContext context, EntityKey key) {
        synchronized (monitor) {
            return state.history.getOrDefault(historyKey(context, key), List.of()).stream().toList();
        }
    }

    private HistorySnapshot findAtTime(RequestContext context, EntityKey key,
                                       Instant validTime, Instant recordedTime) {
        return TemporalHistory.at(findHistory(context, key), validTime, recordedTime);
    }

    private static <T> List<T> page(List<T> values, QueryOptions options) {
        int from = Math.min(options.offset(), values.size());
        int to = Math.min(from + options.limit(), values.size());
        return List.copyOf(values.subList(from, to));
    }

    private static String objectKey(RequestContext context, String type, String id) {
        return context.tenantId().length() + ":" + context.tenantId() + type.length() + ":" + type + (id == null ? "null:" : id.length() + ":" + id);
    }

    private static String linkKey(RequestContext context, String type, String id) {
        return context.tenantId().length() + ":" + context.tenantId() + type.length() + ":" + type + (id == null ? "null:" : id.length() + ":" + id);
    }

    private static String historyKey(RequestContext context, EntityKey key) {
        return objectKey(context, key.type(), key.id());
    }

    private static EntityKey endpointFrom(HistorySnapshot snapshot) {
        return new EntityKey(String.valueOf(snapshot.state().get("_fromType")),
                String.valueOf(snapshot.state().get("_fromId")));
    }

    private static EntityKey endpointTo(HistorySnapshot snapshot) {
        return new EntityKey(String.valueOf(snapshot.state().get("_toType")),
                String.valueOf(snapshot.state().get("_toId")));
    }

    private final class MemoryTransaction implements Transaction {
        private final RequestContext context;
        private final long baseRevision;
        private State working;
        private final String transactionId = UUID.randomUUID().toString();
        private boolean closed;
        private final UniquePropertyIndex uniqueProperties = new UniquePropertyIndex();
        private final OntologySchema schema = InMemoryStorageProvider.this.schema;
        private final String transactionSchemaId = schemaId;

        private MemoryTransaction(RequestContext context, long baseRevision, State working) {
            this.context = context;
            this.baseRevision = baseRevision;
            this.working = working;
        }

        private void requireObjectType(String type) {
            if (schema == null || schema.objectTypes().stream().noneMatch(candidate -> candidate.name().equals(type))) {
                throw new IllegalArgumentException("unknown object type: " + type);
            }
        }

        private List<org.openfoundry.foundation.spi.schema.PropertyDefinition> objectProperties(String type) {
            requireObjectType(type);
            return schema.objectTypes().stream().filter(candidate -> candidate.name().equals(type)).findFirst().orElseThrow().properties();
        }

        private List<String> objectConstraints(String type) {
            requireObjectType(type);
            return schema.objectTypes().stream().filter(candidate -> candidate.name().equals(type)).findFirst().orElseThrow().constraints();
        }

        private LinkTypeDefinition requireLinkType(String type) {
            if (schema == null) {
                throw new IllegalStateException("schema has not been applied");
            }
            return schema.linkTypes().stream()
                    .filter(candidate -> candidate.name().equals(type))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown link type: " + type));
        }

        @Override
        public String transactionId() {
            return transactionId;
        }

        @Override
        public ObjectRecord createObject(String type, String id, Map<String, Object> properties) {
            return createObject(type, id, properties, null);
        }

        @Override
        public ObjectRecord createObject(String type, String id, Map<String, Object> properties, Instant effectiveAt) {
            assertOpen();
            requireObjectType(type);
            String key = objectKey(context, type, id);
            if (working.objects.containsKey(key)) {
                throw new IllegalStateException("object already exists: " + type + ":" + id);
            }
            Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            properties = propertyValidator.validate(schema, objectProperties(type), objectConstraints(type), id, properties, null, context, now);
            uniqueProperties.check(schema, "object", type, objectProperties(type), id, properties, () -> currentProperties(false, type));
            Instant effective = TemporalHistory.effectiveAt(effectiveAt, now, working.history.getOrDefault(historyKey(context, new EntityKey(type, id)), List.of()));
            ObjectRecord object = new ObjectRecord(context.tenantId(), type, id, 1,
                    now, now, null, transactionId, null, properties);
            working.objects.put(key, object);
            appendHistory(new EntityKey(type, id), 1, EntityOperation.CREATED,
                    effective, null, now, properties);
            uniqueProperties.applied(schema, "object", type, objectProperties(type), id, null, properties);
            return object;
        }

        @Override
        public ObjectRecord updateObject(String type, String id, Map<String, Object> properties,
                                         long expectedVersion) {
            return updateObject(type, id, properties, expectedVersion, null);
        }

        @Override
        public ObjectRecord updateObject(String type, String id, Map<String, Object> properties,
                                         long expectedVersion, Instant effectiveAt) {
            return writeObjectProperties(type, id, properties, expectedVersion, effectiveAt, false);
        }

        @Override
        public ObjectRecord restoreObjectProperties(String type, String id, Map<String, Object> properties, long expectedVersion) {
            return writeObjectProperties(type, id, properties, expectedVersion, null, true);
        }

        private ObjectRecord writeObjectProperties(String type, String id, Map<String, Object> properties,
                                                    long expectedVersion, Instant effectiveAt, boolean replace) {
            assertOpen();
            requireObjectType(type);
            String key = objectKey(context, type, id);
            ObjectRecord existing = requireObject(working.objects.get(key), type, id);
            assertVersion(existing.version(), expectedVersion);
            if (existing.isDeleted()) throw new IllegalStateException("Entity is already deleted");
            Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            Map<String, Object> merged = replace
                    ? propertyValidator.restore(schema, objectProperties(type), objectConstraints(type), id, properties, existing.properties(), context, now)
                    : propertyValidator.validate(schema, objectProperties(type), objectConstraints(type), id, properties, existing.properties(), context, now);
            uniqueProperties.check(schema, "object", type, objectProperties(type), id, merged, () -> currentProperties(false, type));
            Instant effective = TemporalHistory.effectiveAt(effectiveAt, now, working.history.getOrDefault(historyKey(context, new EntityKey(type, id)), List.of()));
            ObjectRecord updated = new ObjectRecord(context.tenantId(), type, id,
                    existing.version() + 1, existing.createdAt(), now, existing.deletedAt(),
                    transactionId, null, merged);
            working.objects.put(key, updated);
            appendHistory(new EntityKey(type, id), updated.version(), EntityOperation.UPDATED,
                    effective, null, now, merged);
            uniqueProperties.applied(schema, "object", type, objectProperties(type), id, existing.properties(), merged);
            return updated;
        }

        @Override
        public void deleteObject(String type, String id, long expectedVersion) {
            deleteObject(type, id, expectedVersion, null);
        }

        @Override
        public void deleteObject(String type, String id, long expectedVersion, Instant effectiveAt) {
            assertOpen();
            String key = objectKey(context, type, id);
            ObjectRecord existing = requireObject(working.objects.get(key), type, id);
            assertVersion(existing.version(), expectedVersion);
            if (existing.isDeleted()) throw new IllegalStateException("Entity is already deleted");
            Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            Instant effective = TemporalHistory.effectiveAt(effectiveAt, now, working.history.getOrDefault(historyKey(context, new EntityKey(type, id)), List.of()));
            ObjectRecord deleted = new ObjectRecord(context.tenantId(), type, id,
                    existing.version() + 1, existing.createdAt(), now, effective,
                    transactionId, null, existing.properties());
            working.objects.put(key, deleted);
            appendHistory(new EntityKey(type, id), deleted.version(), EntityOperation.DELETED,
                    effective, null, now, existing.properties());
            uniqueProperties.applied(schema, "object", type, objectProperties(type), id, existing.properties(), null);
        }

        @Override
        public LinkRecord createLink(String type, String id, EntityKey from, EntityKey to,
                                     Map<String, Object> properties) {
            return createLink(type, id, from, to, properties, null);
        }

        @Override
        public LinkRecord createLink(String type, String id, EntityKey from, EntityKey to,
                                     Map<String, Object> properties, Instant effectiveAt) {
            assertOpen();
            LinkTypeDefinition definition = requireLinkType(type);
            requireActiveObject(from);
            requireActiveObject(to);
            String key = linkKey(context, type, id);
            if (working.links.containsKey(key)) {
                throw new IllegalStateException("link already exists: " + type + ":" + id);
            }
            Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            properties = propertyValidator.validate(schema, requireLinkType(type).properties(), requireLinkType(type).constraints(), id, properties, null, context, now);
            uniqueProperties.check(schema, "link", type, requireLinkType(type).properties(), id, properties, () -> currentProperties(true, type));
            Instant effective = TemporalHistory.effectiveAt(effectiveAt, now, working.history.getOrDefault(historyKey(context, new EntityKey(type, id)), List.of()));
            if (!definition.fromType().equals(from.type()) || !definition.toType().equals(to.type())) {
                throw new IllegalArgumentException("Link endpoint types do not match schema");
            }
            requireHistoricalEndpoint(from, effective, now);
            requireHistoricalEndpoint(to, effective, now);
            enforceCardinality(definition, from, to);
            enforceHistoricalCardinality(definition, from, to, effective, now);
            LinkRecord link = new LinkRecord(context.tenantId(), type, id, from, to, 1,
                    now, now, null, effective, null, transactionId, null, properties);
            working.links.put(key, link);
            appendHistory(new EntityKey(type, id), 1, EntityOperation.CREATED,
                    effective, null, now, linkState(link));
            uniqueProperties.applied(schema, "link", type, requireLinkType(type).properties(), id, null, properties);
            return link;
        }

        @Override
        public LinkRecord updateLink(String type, String id, Map<String, Object> properties,
                                     long expectedVersion) {
            return updateLink(type, id, properties, expectedVersion, null);
        }

        @Override
        public LinkRecord updateLink(String type, String id, Map<String, Object> properties,
                                     long expectedVersion, Instant effectiveAt) {
            assertOpen();
            String key = linkKey(context, type, id);
            LinkRecord existing = requireLink(working.links.get(key), type, id);
            assertVersion(existing.version(), expectedVersion);
            if (existing.isDeleted()) throw new IllegalStateException("Entity is already deleted");
            Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            Map<String, Object> merged = propertyValidator.validate(schema, requireLinkType(type).properties(), requireLinkType(type).constraints(), id, properties, existing.properties(), context, now);
            uniqueProperties.check(schema, "link", type, requireLinkType(type).properties(), id, merged, () -> currentProperties(true, type));
            Instant effective = TemporalHistory.effectiveAt(effectiveAt, now, working.history.getOrDefault(historyKey(context, new EntityKey(type, id)), List.of()));
            LinkRecord updated = new LinkRecord(context.tenantId(), type, id, existing.from(),
                    existing.to(), existing.version() + 1, existing.createdAt(), now,
                    existing.deletedAt(), existing.validFrom(), existing.validTo(),
                    transactionId, null, merged);
            working.links.put(key, updated);
            appendHistory(new EntityKey(type, id), updated.version(), EntityOperation.UPDATED,
                    effective, null, now, linkState(updated));
            uniqueProperties.applied(schema, "link", type, requireLinkType(type).properties(), id, existing.properties(), merged);
            return updated;
        }

        @Override
        public void deleteLink(String type, String id, long expectedVersion) {
            deleteLink(type, id, expectedVersion, null);
        }

        @Override
        public void deleteLink(String type, String id, long expectedVersion, Instant effectiveAt) {
            assertOpen();
            String key = linkKey(context, type, id);
            LinkRecord existing = requireLink(working.links.get(key), type, id);
            assertVersion(existing.version(), expectedVersion);
            if (existing.isDeleted()) throw new IllegalStateException("Entity is already deleted");
            Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            Instant effective = TemporalHistory.effectiveAt(effectiveAt, now, working.history.getOrDefault(historyKey(context, new EntityKey(type, id)), List.of()));
            LinkRecord deleted = new LinkRecord(context.tenantId(), type, id, existing.from(),
                    existing.to(), existing.version() + 1, existing.createdAt(), now, effective,
                    existing.validFrom(), effective, transactionId, null, existing.properties());
            working.links.put(key, deleted);
            appendHistory(new EntityKey(type, id), deleted.version(), EntityOperation.DELETED,
                    effective, null, now, linkState(deleted));
            uniqueProperties.applied(schema, "link", type, requireLinkType(type).properties(), id, existing.properties(), null);
        }

        @Override
        public LinkRecord restoreLink(String type, String id, long expectedVersion) {
            assertOpen();
            var definition = requireLinkType(type);
            var key = linkKey(context, type, id);
            var existing = requireLink(working.links.get(key), type, id);
            assertVersion(existing.version(), expectedVersion);
            if (!existing.isDeleted()) throw new IllegalStateException("Relationship is not terminated");
            if (!definition.fromType().equals(existing.from().type()) || !definition.toType().equals(existing.to().type())) {
                throw new IllegalArgumentException("Relationship endpoints no longer match the active schema");
            }
            requireActiveObject(existing.from());
            requireActiveObject(existing.to());
            enforceCardinality(definition, existing.from(), existing.to());
            Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            Instant effective = TemporalHistory.effectiveAt(null, now, working.history.getOrDefault(historyKey(context, new EntityKey(type, id)), List.of()));
            var properties = propertyValidator.validate(schema, definition.properties(), definition.constraints(), id,
                    Map.of(), existing.properties(), context, now);
            uniqueProperties.check(schema, "link", type, definition.properties(), id, properties, () -> currentProperties(true, type));
            var restored = new LinkRecord(context.tenantId(), type, id, existing.from(), existing.to(), existing.version() + 1,
                    existing.createdAt(), now, null, effective, null, transactionId, null, properties);
            working.links.put(key, restored);
            appendHistory(new EntityKey(type, id), restored.version(), EntityOperation.RESTORED, effective, null, now, linkState(restored));
            uniqueProperties.applied(schema, "link", type, definition.properties(), id, null, properties);
            return restored;
        }

        @Override
        public void acquireWrite() {
            assertOpen();
        }

        @Override
        public ObjectRecord getObject(String type, String id) {
            assertOpen();
            return working.objects.get(objectKey(context, type, id));
        }

        @Override
        public LinkRecord getLink(String type, String id) {
            assertOpen();
            return working.links.get(linkKey(context, type, id));
        }

        @Override
        public List<LinkRecord> findLinks(String type, EntityKey from, EntityKey to) {
            assertOpen();
            requireLinkType(type);
            if (from == null && to == null) throw new IllegalArgumentException("A relationship endpoint is required");
            return working.links.values().stream()
                    .filter(link -> link.tenantId().equals(context.tenantId()) && link.type().equals(type) && !link.isDeleted())
                    .filter(link -> from == null || link.from().equals(from))
                    .filter(link -> to == null || link.to().equals(to))
                    .sorted(Comparator.comparing(LinkRecord::id)).toList();
        }

        @Override
        public org.openfoundry.foundation.spi.CommandReceipt getCommandReceipt(String key) {
            assertOpen();
            var receipt = working.receipts.get(objectKey(context, "__command_receipt", key));
            if (receipt != null && !receipt.actorId().equals(context.actorId())) throw new SecurityException("Command receipt belongs to another actor");
            return receipt;
        }

        @Override
        public void putCommandReceipt(org.openfoundry.foundation.spi.CommandReceipt receipt) {
            assertOpen();
            if (!receipt.actorId().equals(context.actorId())) throw new SecurityException("Command receipt actor mismatch");
            String key = objectKey(context, "__command_receipt", receipt.key());
            if (working.receipts.putIfAbsent(key, receipt) != null) throw new IllegalStateException("Command receipt already exists");
        }

        @Override
        public List<LinkRecord> connectedLinks(EntityKey endpoint) {
            assertOpen();
            return working.links.values().stream()
                    .filter(link -> link.tenantId().equals(context.tenantId()) && !link.isDeleted())
                    .filter(link -> link.from().equals(endpoint) || link.to().equals(endpoint))
                    .sorted(Comparator.comparing(LinkRecord::type).thenComparing(LinkRecord::id)).toList();
        }

        @Override
        public org.openfoundry.foundation.spi.ActionExecution getActionExecution(String id) {
            assertOpen();
            var execution = working.executions.get(objectKey(context, "__action_execution", id));
            if (execution != null && !Objects.equals(execution.actorId(), context.actorId())) throw new SecurityException("Action execution belongs to another actor");
            return execution;
        }

        @Override
        public void putActionExecution(org.openfoundry.foundation.spi.ActionExecution execution, long expectedVersion) {
            assertOpen();
            if (!Objects.equals(execution.actorId(), context.actorId()) || execution.version() != expectedVersion + 1) {
                throw new IllegalArgumentException("Invalid execution identity or version");
            }
            var previous = getActionExecution(execution.id());
            if ((previous == null ? 0 : previous.version()) != expectedVersion) throw new IllegalStateException("Action execution version conflict");
            if (previous != null && !previous.action().equals(execution.action())) throw new IllegalArgumentException("Action identity cannot change");
            working.executions.put(objectKey(context, "__action_execution", execution.id()), execution);
        }

        @Override
        public void appendAudit(AuditEntry audit) {
            assertOpen();
            if (!audit.tenantId().equals(context.tenantId())) throw new IllegalArgumentException("audit tenant mismatch");
            if (working.audits.stream().anyMatch(existing -> existing.id().equals(audit.id()))) {
                throw new IllegalArgumentException("duplicate audit id: " + audit.id());
            }
            working.audits.add(audit);
        }

        @Override
        public void enqueueOutbox(OutboxEntry event) {
            assertOpen();
            if (!event.tenantId().equals(context.tenantId())) throw new IllegalArgumentException("event tenant mismatch");
            if (working.outbox.stream().anyMatch(existing -> existing.id().equals(event.id()))) {
                throw new IllegalArgumentException("duplicate outbox id: " + event.id());
            }
            working.outbox.add(event);
        }

        @Override
        public void commit() {
            assertOpen();
            synchronized (monitor) {
                assertOpen();
                if (revision != baseRevision) {
                    throw new org.openfoundry.foundation.spi.TransactionConflictException("transaction conflict: storage changed during transaction");
                }
                state = working;
                revision++;
            }
            closed = true;
        }

        @Override
        public void rollback() {
            if (!closed) {
                closed = true;
                working = null;
            }
        }

        @Override
        public void close() {
            rollback();
        }

        private void appendHistory(EntityKey key, long version, EntityOperation operation,
                                   Instant validFrom, Instant validTo, Instant recordedAt,
                                   Map<String, Object> snapshot) {
            working.history.computeIfAbsent(historyKey(context, key), ignored -> new ArrayList<>())
                    .add(new HistorySnapshot(key, version, operation, validFrom, validTo,
                            recordedAt, transactionId, null, context.actorId(), null, snapshot));
        }

        private void enforceCardinality(LinkTypeDefinition definition, EntityKey from, EntityKey to) {
            if (!definition.fromType().equals(from.type()) || !definition.toType().equals(to.type())) {
                throw new IllegalArgumentException("Link endpoint types do not match schema");
            }
            List<LinkRecord> active = working.links.values().stream()
                    .filter(link -> link.tenantId().equals(context.tenantId()))
                    .filter(link -> link.type().equals(definition.name()))
                    .filter(link -> !link.isDeleted())
                    .toList();
            boolean fromTaken = active.stream().anyMatch(link -> link.from().equals(from));
            boolean toTaken = active.stream().anyMatch(link -> link.to().equals(to));
            if (definition.cardinality() == Cardinality.ONE_TO_ONE && (fromTaken || toTaken)) {
                throw new IllegalStateException("one-to-one link cardinality violated: " + definition.name());
            }
            if (definition.cardinality() == Cardinality.ONE_TO_MANY && toTaken) {
                throw new IllegalStateException("one-to-many link cardinality violated: " + definition.name());
            }
            if (definition.cardinality() == Cardinality.MANY_TO_ONE && fromTaken) {
                throw new IllegalStateException("many-to-one link cardinality violated: " + definition.name());
            }
        }

        private Map<String, Map<String, Object>> currentProperties(boolean link, String type) {
            var result = new HashMap<String, Map<String, Object>>();
            if (link) {
                working.links.values().stream().filter(record -> record.tenantId().equals(context.tenantId()) && record.type().equals(type) && !record.isDeleted())
                        .forEach(record -> result.put(record.id(), record.properties()));
            } else {
                working.objects.values().stream().filter(record -> record.tenantId().equals(context.tenantId()) && record.type().equals(type) && !record.isDeleted())
                        .forEach(record -> result.put(record.id(), record.properties()));
            }
            return result;
        }

        private void requireHistoricalEndpoint(EntityKey key, Instant effective, Instant recorded) {
            if (!TemporalHistory.active(TemporalHistory.at(working.history.getOrDefault(historyKey(context, key), List.of()), effective, recorded))) {
                throw new IllegalArgumentException("Link endpoint did not exist at effective time: " + key);
            }
        }

        private void enforceHistoricalCardinality(LinkTypeDefinition definition, EntityKey from, EntityKey to, Instant effective, Instant recorded) {
            if (definition.cardinality() == Cardinality.MANY_TO_MANY) return;
            for (var link : working.links.values()) {
                if (!link.tenantId().equals(context.tenantId()) || !link.type().equals(definition.name())) continue;
                boolean conflicting = switch (definition.cardinality()) {
                    case ONE_TO_ONE -> link.from().equals(from) || link.to().equals(to);
                    case ONE_TO_MANY -> link.to().equals(to);
                    case MANY_TO_ONE -> link.from().equals(from);
                    default -> false;
                };
                if (!conflicting) continue;
                var history = working.history.getOrDefault(historyKey(context, new EntityKey(link.type(), link.id())), List.of());
                var boundaries = new ArrayList<Instant>();
                boundaries.add(effective);
                history.stream().map(HistorySnapshot::validFrom).filter(time -> !time.isBefore(effective) && !time.isAfter(recorded)).forEach(boundaries::add);
                if (boundaries.stream().anyMatch(time -> TemporalHistory.active(TemporalHistory.at(history, time, recorded)))) {
                    throw new IllegalStateException("Historical link cardinality overlap");
                }
            }
        }

        private void requireActiveObject(EntityKey key) {
            ObjectRecord object = working.objects.get(objectKey(context, key.type(), key.id()));
            if (object == null || object.isDeleted()) {
                throw new IllegalStateException("link endpoint is not an active object: " + key);
            }
        }

        private void assertOpen() {
            if (!Objects.equals(transactionSchemaId, schemaId)) throw new org.openfoundry.foundation.spi.SchemaVersionMismatchException();
            if (closed) {
                throw new IllegalStateException("transaction is closed");
            }
        }
    }

    private static ObjectRecord requireObject(ObjectRecord object, String type, String id) {
        if (object == null) {
            throw new IllegalArgumentException("object not found: " + type + ":" + id);
        }
        return object;
    }

    private static LinkRecord requireLink(LinkRecord link, String type, String id) {
        if (link == null) {
            throw new IllegalArgumentException("link not found: " + type + ":" + id);
        }
        return link;
    }

    private static void assertVersion(long actual, long expected) {
        if (actual != expected) {
            throw new IllegalStateException("version conflict: expected " + expected + ", current " + actual);
        }
    }

    private static Map<String, Object> linkState(LinkRecord link) {
        Map<String, Object> state = new HashMap<>(link.properties());
        state.put("_fromType", link.from().type());
        state.put("_fromId", link.from().id());
        state.put("_toType", link.to().type());
        state.put("_toId", link.to().id());
        return state;
    }

    private static final class State {
        private final Map<String, ObjectRecord> objects;
        private final Map<String, LinkRecord> links;
        private final Map<String, List<HistorySnapshot>> history;
        private final List<AuditEntry> audits;
        private final List<OutboxEntry> outbox;
        private final Map<String, org.openfoundry.foundation.spi.CommandReceipt> receipts;
        private final Map<String, org.openfoundry.foundation.spi.ActionExecution> executions;

        private State() {
            this(new HashMap<>(), new HashMap<>(), new HashMap<>(), new ArrayList<>(), new ArrayList<>(), new HashMap<>(), new HashMap<>());
        }

        private State(Map<String, ObjectRecord> objects, Map<String, LinkRecord> links,
                      Map<String, List<HistorySnapshot>> history, List<AuditEntry> audits,
                      List<OutboxEntry> outbox, Map<String, org.openfoundry.foundation.spi.CommandReceipt> receipts,
                      Map<String, org.openfoundry.foundation.spi.ActionExecution> executions) {
            this.objects = objects;
            this.links = links;
            this.history = history;
            this.audits = audits;
            this.outbox = outbox;
            this.receipts = receipts;
            this.executions = executions;
        }

        private State copy() {
            Map<String, List<HistorySnapshot>> copiedHistory = new HashMap<>();
            history.forEach((key, value) -> copiedHistory.put(key, new ArrayList<>(value)));
            return new State(new HashMap<>(objects), new HashMap<>(links), copiedHistory,
                    new ArrayList<>(audits), new ArrayList<>(outbox), new HashMap<>(receipts), new HashMap<>(executions));
        }
    }
}
