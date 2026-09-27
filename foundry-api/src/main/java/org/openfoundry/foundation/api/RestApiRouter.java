package org.openfoundry.foundation.api;

import org.openfoundry.foundation.actions.ActionManifest;
import org.openfoundry.foundation.security.SecurityPrincipal;
import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.QueryOptions;
import org.openfoundry.foundation.spi.RequestContext;

import java.util.Map;

/** Framework-neutral REST route contract; an HTTP adapter can delegate to this router. */
public final class RestApiRouter {
    private final ApplicationService application;

    public RestApiRouter(ApplicationService application) {
        this.application = application;
    }

    public ApiResponse get(RequestContext context, SecurityPrincipal principal,
                           String path, QueryOptions options) {
        String[] parts = path.split("/");
        if (parts.length < 4 || !"api".equals(parts[1]) || !"v1".equals(parts[2])) return ApiResponse.notFound();
        String type = parts[3];
        if (parts.length == 4) return ApiResponse.ok(application.listObjects(context, principal, type, options));
        if (parts.length == 5) {
            Object value = application.getObject(context, principal, type, parts[4]);
            return value == null ? ApiResponse.notFound() : ApiResponse.ok(value);
        }
        if (parts.length == 6 && "history".equals(parts[5])) {
            return ApiResponse.ok(application.history(context, principal, new EntityKey(type, parts[4])));
        }
        if (parts.length == 7 && "computed".equals(parts[5])) {
            try {
                return ApiResponse.ok(application.readComputedField(context, principal, new EntityKey(type, parts[4]), parts[6], options));
            } catch (IllegalArgumentException invalid) {
                return ApiResponse.badRequest("Invalid computed field query");
            }
        }
        if (parts.length == 7 && "links".equals(parts[5])) {
            try {
                return ApiResponse.ok(application.readLinkField(context, principal, new EntityKey(type, parts[4]), parts[6], options));
            } catch (IllegalArgumentException invalid) {
                return ApiResponse.badRequest("Invalid relationship query");
            }
        }
        return ApiResponse.notFound();
    }

    public ApiResponse query(RequestContext context, SecurityPrincipal principal, String type, ObjectQuery query) {
        try {
            return ApiResponse.ok(application.queryObjects(context, principal, type, query).connection());
        } catch (SecurityException denied) {
            return ApiResponse.forbidden();
        } catch (IllegalArgumentException invalid) {
            return ApiResponse.badRequest("Invalid object query");
        }
    }

    public ApiResponse aggregate(RequestContext context, SecurityPrincipal principal, String type, AggregateQuery query) {
        try {
            return ApiResponse.ok(application.aggregateObjects(context, principal, type, query));
        } catch (SecurityException denied) {
            return ApiResponse.forbidden();
        } catch (IllegalArgumentException invalid) {
            return ApiResponse.badRequest("Invalid object aggregate");
        }
    }

    public ApiResponse resume(RequestContext context, SecurityPrincipal principal, String actionName, String actionId) {
        try {
            return ApiResponse.ok(application.resume(context, principal, actionName, actionId));
        } catch (SecurityException denied) {
            return ApiResponse.forbidden();
        } catch (IllegalArgumentException invalid) {
            return ApiResponse.badRequest("Invalid Action continuation");
        }
    }

    public ApiResponse execute(RequestContext context, SecurityPrincipal principal,
                               String actionName, ActionManifest manifest,
                               Map<String, Object> parameters, String idempotencyKey) {
        if (!manifest.action().equals(actionName)) return ApiResponse.badRequest("action name mismatch");
        try {
            return ApiResponse.ok(application.execute(manifest, context, principal, parameters, idempotencyKey));
        } catch (SecurityException denied) {
            return ApiResponse.forbidden();
        } catch (org.openfoundry.foundation.spi.schema.PropertyValidationException invalid) {
            return new ApiResponse(400, Map.of("error", "Property validation failed", "code", invalid.code(), "field", invalid.field()));
        } catch (org.openfoundry.foundation.actions.LinkResolutionException invalid) {
            return new ApiResponse(400, Map.of("error", "Relationship selection failed", "code", invalid.code()));
        } catch (IllegalArgumentException invalid) {
            return ApiResponse.badRequest("Invalid Action request");
        }
    }
}
