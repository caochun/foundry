package org.openfoundry.foundation.events;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

final class DeliveryTestClock extends Clock {
    static final Instant START = Instant.parse("2030-01-01T00:00:00Z");
    volatile Instant now = START;

    void advance(long seconds) { now = now.plusSeconds(seconds); }
    @Override public Instant instant() { return now; }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
}
