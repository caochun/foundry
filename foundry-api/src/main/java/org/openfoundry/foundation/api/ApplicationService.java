package org.openfoundry.foundation.api;

import org.openfoundry.foundation.actions.ActionActor;
import org.openfoundry.foundation.actions.ActionExecutor;
import org.openfoundry.foundation.actions.ActionManifest;
import org.openfoundry.foundation.actions.ActionResult;
import org.openfoundry.foundation.security.AuthorizationService;
import org.openfoundry.foundation.security.SecurityPrincipal;
import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.HistorySnapshot;
import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.QueryOptions;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.StorageProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Shared application boundary used by REST, GraphQL and future adapters. */
public final class ApplicationService {
    private final StorageProvider storage;
    private final AuthorizationService authorization;
    private final ActionExecutor actions;

    public ApplicationService(StorageProvider storage, AuthorizationService authorization,
                              ActionExecutor actions) {
        this.storage = storage;
        this.authorization = authorization;
        this.actions = actions;
    }

    public ObjectRecord getObject(RequestContext context, SecurityPrincipal principal,
                                  String type, String id) {
        if (!authorization.check(context, principal, "viewer", new EntityKey(type, id))) return null;
        return storage.getObject(context, type, id);
    }

    public List<ObjectRecord> listObjects(RequestContext context, SecurityPrincipal principal,
                                          String type, QueryOptions options) {
        // List authorization is enforced per returned object; providers remain tenant-scoped.
        return storage.queryObjects(context, type, options).stream()
                .filter(object -> authorization.check(context, principal, "viewer", object.key()))
                .toList();
    }

    public List<HistorySnapshot> history(RequestContext context, SecurityPrincipal principal,
                                         EntityKey key) {
        if (!authorization.check(context, principal, "viewer", key)) return List.of();
        return storage.getEntityHistory(context, key);
    }

    public ActionResult execute(ActionManifest manifest, RequestContext context,
                                SecurityPrincipal principal, Map<String, Object> parameters,
                                String idempotencyKey) {
        ActionActor actor = new ActionActor(principal.id(), principal.roles());
        return actions.execute(manifest, null, context, actor, parameters, idempotencyKey, storage);
    }
}
