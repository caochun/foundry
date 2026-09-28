package org.openfoundry.foundation.api;

import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.ObjectRecord;

/** Restricted responses retain only identity; no hidden record or system metadata is retained. */
public record ObjectReadResult(EntityKey key, ObjectRecord object, boolean consentRestricted) {
    public ObjectReadResult {
        java.util.Objects.requireNonNull(key);
        if (consentRestricted ? object != null : object == null || !key.equals(object.key())) throw new IllegalArgumentException("Invalid object projection");
    }
    public static ObjectReadResult visible(ObjectRecord object) { return new ObjectReadResult(object.key(), object, false); }
    public static ObjectReadResult restricted(EntityKey key) { return new ObjectReadResult(key, null, true); }
}
