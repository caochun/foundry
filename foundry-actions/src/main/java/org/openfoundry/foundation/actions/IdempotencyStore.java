package org.openfoundry.foundation.actions;

public interface IdempotencyStore {
    ActionResult get(String key);
    void put(String key, ActionResult result);
}
