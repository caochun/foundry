package org.openfoundry.foundation.events;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local idempotency guard; durable consumers should back it with a store. */
public final class IdempotentEventSink implements EventSink {
    private final EventSink delegate;
    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    public IdempotentEventSink(EventSink delegate) {
        this.delegate = delegate;
    }

    @Override
    public void publish(CloudEvent event) {
        if (seen.add(event.id())) delegate.publish(event);
    }
}
