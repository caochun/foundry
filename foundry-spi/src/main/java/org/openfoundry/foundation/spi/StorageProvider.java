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

    Transaction beginTransaction(RequestContext context);

    StorageCapabilities capabilities();

    enum Direction {
        INBOUND,
        OUTBOUND
    }
}
