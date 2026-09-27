package org.openfoundry.foundation.security;

import org.openfoundry.foundation.spi.EntityKey;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Shared tenant-qualified identity encoding for Check requests and trusted tuple provisioning. */
public final class OpenFgaResourceIds {
    private OpenFgaResourceIds() {}

    public static String user(SecurityPrincipal principal) {
        return "user:" + localId(principal.tenantId(), principal.id());
    }

    public static String resource(String tenantId, EntityKey key) {
        if (!key.type().matches("[A-Za-z][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid OpenFGA resource type");
        return key.type() + ":" + localId(tenantId, key.id());
    }

    private static String localId(String tenantId, String id) {
        if (tenantId == null || tenantId.isBlank() || id == null || id.isBlank()) throw new IllegalArgumentException("Missing resource identity");
        var encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString(tenantId.getBytes(StandardCharsets.UTF_8)) + "." + encoder.encodeToString(id.getBytes(StandardCharsets.UTF_8));
    }
}
