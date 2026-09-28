package org.openfoundry.foundation.api;

import org.openfoundry.foundation.schema.SchemaFingerprint;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.OntologySchema;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** An application/evaluator owns this immutable schema view even when the underlying provider is rebound. */
final class SchemaBoundStorage implements StorageProvider {
    private final StorageProvider delegate;
    private final String configuredFingerprint;
    private final AtomicReference<SchemaBinding> expected = new AtomicReference<>();

    static SchemaBoundStorage bind(StorageProvider provider, OntologySchema schema) {
        String fingerprint = schema == null ? null : SchemaFingerprint.of(schema);
        if (provider instanceof SchemaBoundStorage bound) {
            if (!Objects.equals(bound.configuredFingerprint, fingerprint)) throw new SchemaVersionMismatchException();
            return bound;
        }
        return new SchemaBoundStorage(provider, fingerprint);
    }

    private SchemaBoundStorage(StorageProvider delegate, String fingerprint) {
        this.delegate = Objects.requireNonNull(delegate);
        this.configuredFingerprint = fingerprint;
        captureAvailable();
    }

    private SchemaBinding captureAvailable() {
        var binding = expected.get();
        if (binding != null) return binding;
        var available = delegate.schemaBinding();
        if (available == null) return null;
        if (configuredFingerprint != null && !configuredFingerprint.equals(SchemaFingerprint.of(available.schema()))) {
            throw new SchemaVersionMismatchException();
        }
        expected.compareAndSet(null, available);
        return expected.get();
    }

    private SchemaBinding binding() {
        var binding = captureAvailable();
        if (binding == null) throw new IllegalStateException("Storage schema must be initialized before application execution");
        return binding;
    }

    void requireCurrent(RequestContext context) { delegate.requireSchemaBinding(context, binding()); }

    <T> T read(RequestContext context, Supplier<T> operation) {
        requireCurrent(context);
        try { return operation.get(); }
        finally { requireCurrent(context); }
    }

    @Override public SchemaBinding schemaBinding() { return captureAvailable(); }
    @Override public void requireSchemaBinding(RequestContext context, SchemaBinding requested) {
        if (requested == null || !binding().id().equals(requested.id())) throw new SchemaVersionMismatchException();
        requireCurrent(context);
    }
    @Override public void applySchema(RequestContext context, OntologySchema schema) {
        throw new UnsupportedOperationException("Rebuild the application to change its bound schema");
    }
    @Override public Transaction beginTransaction(RequestContext context) { return delegate.beginTransaction(context, binding()); }
    @Override public Transaction beginTransaction(RequestContext context, SchemaBinding requested) {
        if (requested == null || !binding().id().equals(requested.id())) throw new SchemaVersionMismatchException();
        return delegate.beginTransaction(context, binding());
    }
    @Override public ObjectRecord getObject(RequestContext context, String type, String id) {
        return read(context, () -> delegate.getObject(context, type, id));
    }
    @Override public List<ObjectRecord> queryObjects(RequestContext context, String type, QueryOptions options) {
        return read(context, () -> delegate.queryObjects(context, type, options));
    }
    @Override public HistorySnapshot getObjectAtVersion(RequestContext context, String type, String id, long version) {
        return read(context, () -> delegate.getObjectAtVersion(context, type, id, version));
    }
    @Override public HistorySnapshot getObjectAtTime(RequestContext context, String type, String id, Instant valid, Instant recorded) {
        return read(context, () -> delegate.getObjectAtTime(context, type, id, valid, recorded));
    }
    @Override public LinkRecord getLink(RequestContext context, String type, String id) {
        return read(context, () -> delegate.getLink(context, type, id));
    }
    @Override public List<LinkRecord> getLinks(RequestContext context, EntityKey endpoint, String type, Direction direction, QueryOptions options) {
        return read(context, () -> delegate.getLinks(context, endpoint, type, direction, options));
    }
    @Override public HistorySnapshot getLinkAtVersion(RequestContext context, String type, String id, long version) {
        return read(context, () -> delegate.getLinkAtVersion(context, type, id, version));
    }
    @Override public HistorySnapshot getLinkAtTime(RequestContext context, String type, String id, Instant valid, Instant recorded) {
        return read(context, () -> delegate.getLinkAtTime(context, type, id, valid, recorded));
    }
    @Override public TraversalResult traverseAsOf(RequestContext context, EntityKey start, List<TraversalStep> path, Instant valid, Instant recorded, QueryOptions options) {
        return read(context, () -> delegate.traverseAsOf(context, start, path, valid, recorded, options));
    }
    @Override public List<HistorySnapshot> getEntityHistory(RequestContext context, EntityKey key) {
        return read(context, () -> delegate.getEntityHistory(context, key));
    }
    @Override public List<ActionExecution> pendingActions(RequestContext context, Instant now, int limit) {
        return read(context, () -> delegate.pendingActions(context, now, limit));
    }
    @Override public StorageCapabilities capabilities() { return delegate.capabilities(); }
}
