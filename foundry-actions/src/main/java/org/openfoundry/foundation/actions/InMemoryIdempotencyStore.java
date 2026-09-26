package org.openfoundry.foundation.actions;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryIdempotencyStore implements IdempotencyStore {
    private final Map<String, ActionResult> values = new ConcurrentHashMap<>();
    public ActionResult get(String key) { return values.get(key); }
    public void put(String key, ActionResult result) { values.putIfAbsent(key, result); }
}
