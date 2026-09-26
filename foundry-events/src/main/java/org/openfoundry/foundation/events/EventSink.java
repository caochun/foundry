package org.openfoundry.foundation.events;

@FunctionalInterface
public interface EventSink {
    void publish(CloudEvent event);
}
