package org.openfoundry.foundation.spi;

import org.openfoundry.foundation.spi.schema.OntologySchema;

import java.time.Instant;
import java.util.List;

/** Persistence contract implemented by every database provider. */
public interface StorageProvider {
    void applySchema(RequestContext context, OntologySchema schema);

    ObjectRecord getObject(RequestContext context, String type, String id);

    List<ObjectRecord> queryObjects(RequestContext context, String type,
                                    QueryOptions options);

    HistorySnapshot getObjectAtVersion(RequestContext context, String type,
                                      String id, long version);

    HistorySnapshot getObjectAtTime(RequestContext context, String type,
                                    String id, Instant validTime, Instant recordedTime);

    LinkRecord getLink(RequestContext context, String type, String id);

    List<LinkRecord> getLinks(RequestContext context, EntityKey endpoint,
                              String linkType, Direction direction, QueryOptions options);

    HistorySnapshot getLinkAtVersion(RequestContext context, String type,
                                     String id, long version);

    HistorySnapshot getLinkAtTime(RequestContext context, String type,
                                  String id, Instant validTime, Instant recordedTime);

    TraversalResult traverseAsOf(RequestContext context, EntityKey start,
                                 List<TraversalStep> path, Instant validTime,
                                 Instant recordedTime, QueryOptions options);

    List<HistorySnapshot> getEntityHistory(RequestContext context, EntityKey key);

    default List<FieldProvenance> getLineage(RequestContext context, EntityKey key, LineageQuery query) {
        throw new UnsupportedOperationException("Field lineage is not supported");
    }

    default IngestionCheckpoint getIngestionCheckpoint(RequestContext context, String key) {
        throw new UnsupportedOperationException("Ingestion checkpoints are not supported");
    }

    Transaction beginTransaction(RequestContext context);

    /** Local immutable binding, or null before schema initialization. */
    default SchemaBinding schemaBinding() {
        throw new UnsupportedOperationException("Schema binding is not supported");
    }

    /** Check the supplied activation against both this instance and authoritative shared state. */
    default void requireSchemaBinding(RequestContext context, SchemaBinding expected) {
        throw new UnsupportedOperationException("Schema binding is not supported");
    }

    /** Begin a transaction using exactly this binding; implementations must not silently rebind it. */
    default Transaction beginTransaction(RequestContext context, SchemaBinding expected) {
        throw new UnsupportedOperationException("Bound transactions are not supported");
    }

    default List<ActionExecution> pendingActions(RequestContext context, Instant now, int limit) {
        throw new UnsupportedOperationException("Durable action continuations are not supported");
    }

    StorageCapabilities capabilities();

    enum Direction {
        INBOUND,
        OUTBOUND
    }
}
