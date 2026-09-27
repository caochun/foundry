package org.openfoundry.foundation.events;

import org.openfoundry.foundation.spi.schema.PropertyValues;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;

record EventIdentity(String tenant, String key, String fingerprint) {
    static EventIdentity of(String consumer, CloudEvent event) {
        if (consumer == null || consumer.isBlank() || event.tenantId() == null || event.tenantId().isBlank()
                || event.source() == null || event.source().isBlank() || event.id() == null || event.id().isBlank()) {
            throw new IllegalArgumentException("Consumer, tenant, event source and ID are required");
        }
        var fields = new LinkedHashMap<String, Object>();
        fields.put("version", event.specVersion());
        fields.put("type", event.type());
        fields.put("subject", event.subject());
        fields.put("time", event.time() == null ? null : event.time().toString());
        fields.put("transaction", event.transactionId());
        fields.put("data", event.data());
        return new EventIdentity(event.tenantId(), hash(PropertyValues.canonical(List.of(consumer, event.source(), event.id()))),
                hash(PropertyValues.canonical(fields)));
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
