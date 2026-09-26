package org.openfoundry.foundation.sync;

import java.util.stream.Stream;

public interface Connector {
    String name();

    Stream<SourceRecord> read(SourceQuery query);
}
