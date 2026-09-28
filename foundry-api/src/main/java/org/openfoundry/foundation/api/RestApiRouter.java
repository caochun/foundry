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
    private final ObjectSetService objectSets;

    public RestApiRouter(ApplicationService application) {
        this(application, null);
    }

    public RestApiRouter(ApplicationService application, org.openfoundry.foundation.spi.ObjectSetStore objectSets) {
        this.application = application;
        this.objectSets = objectSets == null ? null : new ObjectSetService(application, objectSets);
    }

    public ApiResponse objectSets(RequestContext context, SecurityPrincipal principal, String method, String path,
                                  Map<String, String> parameters, Map<String, Object> input) {
        if (objectSets == null) return new ApiResponse(501, Map.of("code", "OBJECT_SETS_NOT_CONFIGURED", "error", "ObjectSet store is not configured"));
        var parts = path.split("/");
        try {
            if (parts.length == 4) {
                if (method.equals("GET")) {
                    requireParameters(parameters, java.util.Set.of("name", "objectType"));
                    if (parameters.containsKey("name")) {
                        if (parameters.containsKey("objectType")) throw new IllegalArgumentException("Name lookup cannot include type filtering");
                        var found = objectSets.getByName(context, principal, parameters.get("name"));
                        return found == null ? ApiResponse.notFound() : ApiResponse.ok(found.toMap());
                    }
                    return ApiResponse.ok(objectSets.list(context, principal, parameters.get("objectType")).stream().map(org.openfoundry.foundation.spi.ObjectSetDefinition::toMap).toList());
                }
                if (method.equals("POST")) {
                    requireParameters(parameters, java.util.Set.of());
                    return new ApiResponse(201, objectSets.create(context, principal, input).toMap());
                }
            }
            if (parts.length == 5) {
                String id = parts[4];
                switch (method) {
                    case "GET" -> {
                        requireParameters(parameters, java.util.Set.of());
                        var found = objectSets.get(context, principal, id);
                        return found == null ? ApiResponse.notFound() : ApiResponse.ok(found.toMap());
                    }
                    case "PUT" -> {
                        requireParameters(parameters, java.util.Set.of());
                        var patch = new java.util.LinkedHashMap<>(input);
                        var expected = ObjectSetService.expectedVersion(patch.remove("expectedVersion"));
                        return ApiResponse.ok(objectSets.update(context, principal, id, patch, expected).toMap());
                    }
                    case "DELETE" -> {
                        requireParameters(parameters, java.util.Set.of("expectedVersion"));
                        objectSets.delete(context, principal, id, ObjectSetService.expectedVersion(parameters.get("expectedVersion")));
                        return new ApiResponse(204, Map.of());
                    }
                }
            }
            if (parts.length == 6 && method.equals("GET")) {
                if (parts[5].equals("execute")) {
                    requireParameters(parameters, java.util.Set.of("limit", "offset"));
                    Integer limit = parameters.containsKey("limit") ? Integer.valueOf(parameters.get("limit")) : null;
                    int offset = parameters.containsKey("offset") ? Integer.parseInt(parameters.get("offset")) : 0;
                    return ApiResponse.ok(objectSets.execute(context, principal, parts[4], limit, offset));
                }
                if (parts[5].equals("aggregate")) {
                    requireParameters(parameters, java.util.Set.of());
                    return ApiResponse.ok(objectSets.aggregate(context, principal, parts[4]));
                }
            }
            return ApiResponse.notFound();
        } catch (org.openfoundry.foundation.spi.ObjectSetNotFoundException missing) {
            return new ApiResponse(404, Map.of("code", "OBJECT_SET_NOT_FOUND", "error", "ObjectSet is not available"));
        } catch (org.openfoundry.foundation.spi.ObjectSetConflictException changed) {
            return new ApiResponse(409, Map.of("code", "OBJECT_SET_CONFLICT", "error", "ObjectSet changed; reload its definition", "retryable", false));
        } catch (SecurityException denied) { return ApiResponse.forbidden(); }
        catch (IllegalArgumentException invalid) { return ApiResponse.badRequest("Invalid ObjectSet request"); }
    }

    private static void requireParameters(Map<String, String> input, java.util.Set<String> allowed) {
        if (!allowed.containsAll(input.keySet())) throw new IllegalArgumentException("Unknown ObjectSet query parameter");
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

    public ApiResponse query(RequestContext context, SecurityPrincipal principal, String type, ObjectConnectionQuery query) {
        try {
            return ApiResponse.ok(application.queryConnection(context, principal, type, query));
        } catch (SecurityException denied) {
            return ApiResponse.forbidden();
        } catch (IllegalArgumentException invalid) {
            return ApiResponse.badRequest("Invalid object connection query");
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

    public ApiResponse search(RequestContext context, SecurityPrincipal principal, String type, SearchQuery query) {
        try {
            return ApiResponse.ok(application.searchObjects(context, principal, type, query));
        } catch (SecurityException denied) {
            return ApiResponse.forbidden();
        } catch (IllegalArgumentException invalid) {
            return ApiResponse.badRequest("Invalid object search");
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
