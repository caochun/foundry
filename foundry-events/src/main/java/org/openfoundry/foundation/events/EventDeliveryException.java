package org.openfoundry.foundation.events;

/** A delivery that must not be acknowledged by its caller. */
public final class EventDeliveryException extends IllegalStateException {
    public enum Reason { BUSY, LEASE_LOST, LEGACY_REVIEW_REQUIRED }
    private final Reason reason;

    public EventDeliveryException(Reason reason) {
        super("Event delivery: " + reason);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
