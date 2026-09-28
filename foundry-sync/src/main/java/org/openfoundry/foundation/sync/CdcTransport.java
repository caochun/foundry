package org.openfoundry.foundation.sync;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** Host-owned broker adapter. Disable auto-commit; acknowledge only the supplied record, never a prefetched batch. */
public interface CdcTransport {
    /** Stable logical stream/cluster identity; change it deliberately when rebuilding a source log. */
    String identity();

    /** Fixed assignments for this session; this metadata must not open the transport. */
    Set<Integer> partitions();

    /** Start strictly after each durable offset. Missing assignments start at the host-configured initial position. */
    Session open(Map<Integer, Long> committedOffsets);

    interface Session extends AutoCloseable {
        /** Pull one message; null ends this bounded session. Implementations must bound blocking and prefetch. */
        Message next();

        /** Acknowledge this message only after its local transaction (or a validated no-op) has succeeded. */
        void acknowledge(Message message);

        @Override
        void close();
    }

    record Message(String topic, int partition, long offset, Instant timestamp, String keyJson, String valueJson) {
        public Message {
            if (topic == null || topic.isBlank() || partition < 0 || offset < 0) {
                throw new IllegalArgumentException("Invalid CDC message position");
            }
        }
    }
}
