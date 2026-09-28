package org.openfoundry.foundation.spi;

import java.util.List;

public record ConsentSnapshot(long revision, boolean optedOut, List<ConsentRecord> records) {
    public ConsentSnapshot { records = List.copyOf(records); }
    public static ConsentSnapshot empty() { return new ConsentSnapshot(0, false, List.of()); }
}
