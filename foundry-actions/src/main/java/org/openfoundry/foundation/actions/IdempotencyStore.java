package org.openfoundry.foundation.actions;

import java.util.function.Supplier;

/** Claims and replays one scoped request atomically. Durability is implementation-specific. */
public interface IdempotencyStore {
    ActionResult execute(String scopedKey, String fingerprint, Supplier<ActionResult> work);
}
