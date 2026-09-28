package org.openfoundry.foundation.spi;

import java.util.List;
import java.util.Map;

/** Tenant-scoped saved query metadata, not a grant to the data selected by the query. */
public interface ObjectSetStore {
    ObjectSetDefinition create(RequestContext context, ObjectSetSpec definition);
    ObjectSetDefinition get(RequestContext context, String id);
    ObjectSetDefinition getByName(RequestContext context, String name);
    List<ObjectSetDefinition> list(RequestContext context, String objectType);
    ObjectSetDefinition update(RequestContext context, String id, Map<String, Object> patch, Long expectedVersion);
    void delete(RequestContext context, String id, Long expectedVersion);

    default ObjectSetDefinition update(RequestContext context, String id, Map<String, Object> patch) {
        return update(context, id, patch, null);
    }
    default void delete(RequestContext context, String id) { delete(context, id, null); }
}
