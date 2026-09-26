package org.openfoundry.foundation.api;

public record ApiResponse(int status, Object body) {
    public static ApiResponse ok(Object body) { return new ApiResponse(200, body); }
    public static ApiResponse created(Object body) { return new ApiResponse(201, body); }
    public static ApiResponse badRequest(String message) { return new ApiResponse(400, java.util.Map.of("error", message)); }
    public static ApiResponse unauthorized() { return new ApiResponse(401, java.util.Map.of("error", "unauthorized")); }
    public static ApiResponse forbidden() { return new ApiResponse(403, java.util.Map.of("error", "forbidden")); }
    public static ApiResponse notFound() { return new ApiResponse(404, java.util.Map.of("error", "not found")); }
}
