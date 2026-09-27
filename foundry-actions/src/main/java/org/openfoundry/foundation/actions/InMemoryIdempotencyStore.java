package org.openfoundry.foundation.actions;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Process-local atomic replay. Not a durable transaction receipt or a multi-process coordinator. */
public final class InMemoryIdempotencyStore implements IdempotencyStore {
    private final Map<String, Entry> values = new HashMap<>();

    @Override
    public synchronized ActionResult execute(String scopedKey, String fingerprint, Supplier<ActionResult> work) {
        Entry previous = values.get(scopedKey);
        if (previous != null) {
            if (!previous.fingerprint().equals(fingerprint)) {
                throw new IllegalArgumentException("Idempotency key belongs to a different request");
            }
            return previous.result();
        }
        ActionResult result = work.get();
        if (result.success()) values.put(scopedKey, new Entry(fingerprint, result));
        return result;
    }

    private record Entry(String fingerprint, ActionResult result) {}
}
