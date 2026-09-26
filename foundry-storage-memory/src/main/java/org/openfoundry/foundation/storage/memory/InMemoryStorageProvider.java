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
            true, true, false, false, false, true, false);

    private final Object monitor = new Object();
    private OntologySchema schema;
    private State state = new State();
    private long revision;

    @Override
    public void applySchema(RequestContext context, OntologySchema schema) {
        Objects.requireNonNull(context, "context must not be null");
        this.schema = Objects.requireNonNull(schema, "schema must not be null");
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
            return page(state.objects.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(context.tenantId() + "|" + type + "|"))
                    .map(Map.Entry::getValue)
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
        if (path.size() > 10) {
            throw new IllegalArgumentException("traversal depth exceeds 10");
        }
        List<EntityKey> frontier = List.of(start);
        List<HistorySnapshot> edges = new ArrayList<>();
        synchronized (monitor) {
            for (TraversalStep step : path) {
                List<EntityKey> next = new ArrayList<>();
                for (EntityKey endpoint : frontier) {
                    String prefix = context.tenantId() + "|" + step.linkType() + "|";
                    for (Map.Entry<String, List<HistorySnapshot>> entry : state.history.entrySet()) {
                        if (!entry.getKey().startsWith(prefix)) continue;
                        HistorySnapshot snapshot = entry.getValue().stream()
                                .filter(candidate -> !candidate.recordedAt().isAfter(recordedTime))
                                .filter(candidate -> !validTime.isBefore(candidate.validFrom()))
                                .filter(candidate -> candidate.validTo() == null || validTime.isBefore(candidate.validTo()))
                                .max(Comparator.comparing(HistorySnapshot::recordedAt)
                                        .thenComparingLong(HistorySnapshot::version))
                                .orElse(null);
                        if (snapshot == null) continue;
                        EntityKey from = endpointFrom(snapshot);
                        EntityKey to = endpointTo(snapshot);
                        EntityKey source = step.direction() == StorageProvider.Direction.OUTBOUND ? from : to;
                        EntityKey target = step.direction() == StorageProvider.Direction.OUTBOUND ? to : from;
                        if (source.equals(endpoint)) {
                            edges.add(snapshot);
                            next.add(target);
                        }
                    }
                }
                frontier = next.stream().distinct().toList();
                if (frontier.isEmpty()) break;
            }
        }
        return new TraversalResult(page(frontier, options), edges);
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
        Objects.requireNonNull(validTime, "validTime must not be null");
        Objects.requireNonNull(recordedTime, "recordedTime must not be null");
        return findHistory(context, key).stream()
                .filter(snapshot -> !snapshot.recordedAt().isAfter(recordedTime))
                .filter(snapshot -> !validTime.isBefore(snapshot.validFrom()))
                .filter(snapshot -> snapshot.validTo() == null || validTime.isBefore(snapshot.validTo()))
                .max(Comparator.comparing(HistorySnapshot::recordedAt)
                        .thenComparingLong(HistorySnapshot::version))
                .orElse(null);
    }

    private static <T> List<T> page(List<T> values, QueryOptions options) {
        int from = Math.min(options.offset(), values.size());
        int to = Math.min(from + options.limit(), values.size());
        return List.copyOf(values.subList(from, to));
    }

    private static String objectKey(RequestContext context, String type, String id) {
        return context.tenantId() + "|" + type + "|" + id;
    }

    private static String linkKey(RequestContext context, String type, String id) {
        return context.tenantId() + "|" + type + "|" + id;
    }

    private static String historyKey(RequestContext context, EntityKey key) {
        return context.tenantId() + "|" + key.type() + "|" + key.id();
    }

    private static EntityKey endpointFrom(HistorySnapshot snapshot) {
        return new EntityKey(String.valueOf(snapshot.state().get("_fromType")),
                String.valueOf(snapshot.state().get("_fromId")));
    }

    private static EntityKey endpointTo(HistorySnapshot snapshot) {
        return new EntityKey(String.valueOf(snapshot.state().get("_toType")),
                String.valueOf(snapshot.state().get("_toId")));
    }

    private void requireObjectType(String type) {
        if (schema == null || schema.objectTypes().stream().noneMatch(candidate -> candidate.name().equals(type))) {
            throw new IllegalArgumentException("unknown object type: " + type);
        }
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

    private final class MemoryTransaction implements Transaction {
        private final RequestContext context;
        private final long baseRevision;
        private State working;
        private final String transactionId = UUID.randomUUID().toString();
        private boolean closed;

        private MemoryTransaction(RequestContext context, long baseRevision, State working) {
            this.context = context;
            this.baseRevision = baseRevision;
            this.working = working;
        }

        @Override
        public String transactionId() {
            return transactionId;
        }

        @Override
        public ObjectRecord createObject(String type, String id, Map<String, Object> properties) {
            assertOpen();
            requireObjectType(type);
            String key = objectKey(context, type, id);
            if (working.objects.containsKey(key)) {
                throw new IllegalStateException("object already exists: " + type + ":" + id);
            }
            Instant now = Instant.now();
            ObjectRecord object = new ObjectRecord(context.tenantId(), type, id, 1,
                    now, now, null, transactionId, null, properties);
            working.objects.put(key, object);
            appendHistory(new EntityKey(type, id), 1, EntityOperation.CREATED,
                    now, null, now, properties);
            return object;
        }

        @Override
        public ObjectRecord updateObject(String type, String id, Map<String, Object> properties,
                                         long expectedVersion) {
            assertOpen();
            requireObjectType(type);
            String key = objectKey(context, type, id);
            ObjectRecord existing = requireObject(working.objects.get(key), type, id);
            assertVersion(existing.version(), expectedVersion);
            Map<String, Object> merged = new HashMap<>(existing.properties());
            merged.putAll(properties);
            Instant now = Instant.now();
            ObjectRecord updated = new ObjectRecord(context.tenantId(), type, id,
                    existing.version() + 1, existing.createdAt(), now, existing.deletedAt(),
                    transactionId, null, merged);
            working.objects.put(key, updated);
            appendHistory(new EntityKey(type, id), updated.version(), EntityOperation.UPDATED,
                    existing.updatedAt(), null, now, merged);
            return updated;
        }

        @Override
        public void deleteObject(String type, String id, long expectedVersion) {
            assertOpen();
            String key = objectKey(context, type, id);
            ObjectRecord existing = requireObject(working.objects.get(key), type, id);
            assertVersion(existing.version(), expectedVersion);
            Instant now = Instant.now();
            ObjectRecord deleted = new ObjectRecord(context.tenantId(), type, id,
                    existing.version() + 1, existing.createdAt(), now, now,
                    transactionId, null, existing.properties());
            working.objects.put(key, deleted);
            appendHistory(new EntityKey(type, id), deleted.version(), EntityOperation.DELETED,
                    existing.updatedAt(), now, now, existing.properties());
        }

        @Override
        public LinkRecord createLink(String type, String id, EntityKey from, EntityKey to,
                                     Map<String, Object> properties) {
            assertOpen();
            LinkTypeDefinition definition = requireLinkType(type);
            requireActiveObject(from);
            requireActiveObject(to);
            String key = linkKey(context, type, id);
            if (working.links.containsKey(key)) {
                throw new IllegalStateException("link already exists: " + type + ":" + id);
            }
            enforceCardinality(definition, from, to);
            Instant now = Instant.now();
            LinkRecord link = new LinkRecord(context.tenantId(), type, id, from, to, 1,
                    now, now, null, now, null, transactionId, null, properties);
            working.links.put(key, link);
            appendHistory(new EntityKey(type, id), 1, EntityOperation.CREATED,
                    now, null, now, linkState(link));
            return link;
        }

        @Override
        public LinkRecord updateLink(String type, String id, Map<String, Object> properties,
                                     long expectedVersion) {
            assertOpen();
            String key = linkKey(context, type, id);
            LinkRecord existing = requireLink(working.links.get(key), type, id);
            assertVersion(existing.version(), expectedVersion);
            Map<String, Object> merged = new HashMap<>(existing.properties());
            merged.putAll(properties);
            Instant now = Instant.now();
            LinkRecord updated = new LinkRecord(context.tenantId(), type, id, existing.from(),
                    existing.to(), existing.version() + 1, existing.createdAt(), now,
                    existing.deletedAt(), existing.validFrom(), existing.validTo(),
                    transactionId, null, merged);
            working.links.put(key, updated);
            appendHistory(new EntityKey(type, id), updated.version(), EntityOperation.UPDATED,
                    existing.validFrom(), existing.validTo(), now, linkState(updated));
            return updated;
        }

        @Override
        public void deleteLink(String type, String id, long expectedVersion) {
            assertOpen();
            String key = linkKey(context, type, id);
            LinkRecord existing = requireLink(working.links.get(key), type, id);
            assertVersion(existing.version(), expectedVersion);
            Instant now = Instant.now();
            LinkRecord deleted = new LinkRecord(context.tenantId(), type, id, existing.from(),
                    existing.to(), existing.version() + 1, existing.createdAt(), now, now,
                    existing.validFrom(), now, transactionId, null, existing.properties());
            working.links.put(key, deleted);
            appendHistory(new EntityKey(type, id), deleted.version(), EntityOperation.DELETED,
                    existing.validFrom(), now, now, linkState(deleted));
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
                if (revision != baseRevision) {
                    throw new IllegalStateException("transaction conflict: storage changed during transaction");
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

        private void requireActiveObject(EntityKey key) {
            ObjectRecord object = working.objects.get(objectKey(context, key.type(), key.id()));
            if (object == null || object.isDeleted()) {
                throw new IllegalStateException("link endpoint is not an active object: " + key);
            }
        }

        private void assertOpen() {
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

        private State() {
            this(new HashMap<>(), new HashMap<>(), new HashMap<>(), new ArrayList<>(), new ArrayList<>());
        }

        private State(Map<String, ObjectRecord> objects, Map<String, LinkRecord> links,
                      Map<String, List<HistorySnapshot>> history, List<AuditEntry> audits,
                      List<OutboxEntry> outbox) {
            this.objects = objects;
            this.links = links;
            this.history = history;
            this.audits = audits;
            this.outbox = outbox;
        }

        private State copy() {
            Map<String, List<HistorySnapshot>> copiedHistory = new HashMap<>();
            history.forEach((key, value) -> copiedHistory.put(key, new ArrayList<>(value)));
            return new State(new HashMap<>(objects), new HashMap<>(links), copiedHistory,
                    new ArrayList<>(audits), new ArrayList<>(outbox));
        }
    }
}
