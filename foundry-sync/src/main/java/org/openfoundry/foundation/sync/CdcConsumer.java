package org.openfoundry.foundation.sync;

import org.openfoundry.foundation.spi.IngestionCheckpoint;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.StorageProvider;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** Pull-based CDC ingestion. Broker acknowledgment follows a durable local commit; failures stop consumption. */
public final class CdcConsumer implements AutoCloseable {
    public record Stats(long recordsProcessed, long recordsFailed, long tombstones, long messagesAcknowledged,
                        Map<Integer, Long> committedOffsets, Instant lastProcessedAt, boolean running, String status) {
        public Stats {
            committedOffsets = Map.copyOf(committedOffsets);
        }
    }

    private final DatasourceMapping mapping;
    private final DebeziumDecoder.Binding binding;
    private final MaterializedSyncService baseService;
    private final DebeziumDecoder decoder;
    private final Object gate = new Object();
    private boolean paused;
    private boolean closed;
    private boolean running;
    private volatile CdcTransport.Session activeSession;
    private volatile Stats stats = new Stats(0, 0, 0, 0, Map.of(), null, false, "IDLE");

    public CdcConsumer(StorageProvider storage, DatasourceMapping mapping, DebeziumDecoder.Binding binding, SyncAuthorizer authorizer) {
        this(storage, mapping, binding, authorizer, new TransformRegistry(), Map.of());
    }

    public CdcConsumer(StorageProvider storage, DatasourceMapping mapping, DebeziumDecoder.Binding binding, SyncAuthorizer authorizer,
                       TransformRegistry transforms, Map<String, Integer> sourcePriorities) {
        this.mapping = Objects.requireNonNull(mapping);
        this.binding = Objects.requireNonNull(binding);
        if (mapping.sync().mode() != DatasourceMapping.Mode.CDC || Boolean.TRUE.equals(mapping.sync().writeback())
                || mapping.sync().cacheStrategy() != null || mapping.sync().cacheTTL() != null) {
            throw new IllegalArgumentException("CDC consumer requires a CDC mapping without overlay/writeback options");
        }
        String qualified = binding.schema() == null ? binding.table() : binding.schema() + "." + binding.table();
        if (!mapping.connection().table().equals(binding.table()) && !mapping.connection().table().equals(qualified)) {
            throw new IllegalArgumentException("CDC binding and mapping source tables disagree");
        }
        var strategy = mapping.sync().conflictResolution() == null ? ConflictResolver.Strategy.LAST_WRITE_WINS : mapping.sync().conflictResolution();
        baseService = new MaterializedSyncService(storage, new ConflictResolver(strategy, Map.of(), sourcePriorities))
                .withAuthorization(authorizer).withTransforms(transforms);
        decoder = new DebeziumDecoder(mapping.datasource(), mapping.mapping().primaryKey().source(), binding);
    }

    public Stats stats() {
        return stats;
    }

    public void pause() {
        synchronized (gate) {
            requireOpen();
            paused = true;
        }
    }

    public void resume() {
        synchronized (gate) {
            requireOpen();
            paused = false;
            gate.notifyAll();
        }
    }

    @Override
    public void close() {
        synchronized (gate) {
            closed = true;
            var session = activeSession;
            if (session != null) {
                session.close();
            }
            gate.notifyAll();
        }
    }

    public IngestionCheckpoint checkpoint(String transportIdentity, int partition, RequestContext context) {
        return service(transportIdentity).checkpoint(mapping.datasource(), mapping.mapping(), DebeziumDecoder.partition(binding.topic(), partition), context);
    }

    public MaterializedSyncService.SyncResult consume(CdcTransport transport, RequestContext context) {
        synchronized (gate) {
            requireOpen();
            if (running) {
                throw new IllegalStateException("This CDC consumer is already running");
            }
            running = true;
            stats = new Stats(0, 0, 0, 0, Map.of(), null, true, "STARTING");
        }
        try {
            String identity = transport.identity();
            Set<Integer> partitions = Set.copyOf(transport.partitions());
            if (partitions.isEmpty() || partitions.stream().anyMatch(p -> p < 0)) {
                throw new IllegalArgumentException("CDC transport needs non-negative partition assignments");
            }
            MaterializedSyncService service = service(identity);
            var offsets = new LinkedHashMap<Integer, Long>();
            for (int partition : partitions) {
                var checkpoint = service.checkpoint(mapping.datasource(), mapping.mapping(), DebeziumDecoder.partition(binding.topic(), partition), context);
                if (checkpoint != null) {
                    offsets.put(partition, checkpoint.sequence());
                }
            }
            stats = new Stats(0, 0, 0, 0, offsets, null, true, "RUNNING");
            var connector = new SessionConnector(transport, identity, partitions, offsets);
            var result = service.sync(connector, new SourceQuery(binding.table(), Map.of()), mapping.mapping(), context, connector::committed);
            var previous = stats;
            stats = new Stats(previous.recordsProcessed(), result.failures().size(), previous.tombstones(), previous.messagesAcknowledged(),
                    previous.committedOffsets(), previous.lastProcessedAt(), false, result.failures().isEmpty() ? (closed ? "CLOSED" : "COMPLETED") : "FAILED");
            return result;
        } catch (RuntimeException | Error failure) {
            var previous = stats;
            stats = new Stats(previous.recordsProcessed(), previous.recordsFailed() + 1, previous.tombstones(), previous.messagesAcknowledged(),
                    previous.committedOffsets(), previous.lastProcessedAt(), false, "FAILED");
            throw failure;
        } finally {
            synchronized (gate) {
                running = false;
                gate.notifyAll();
            }
        }
    }

    private MaterializedSyncService service(String identity) {
        if (identity == null || identity.isBlank()) {
            throw new IllegalArgumentException("Stable CDC transport identity is required");
        }
        return baseService.withSourceConfiguration(Map.of("format", "debezium-consumer-v1", "transport", identity,
                "binding", binding.definition(), "connector", mapping.connector(), "url", mapping.connection().url(),
                "properties", mapping.connection().properties(), "table", mapping.connection().table()));
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("CDC consumer is closed");
        }
    }

    private final class SessionConnector implements Connector {
        private final CdcTransport transport;
        private final String identity;
        private final Set<Integer> partitions;
        private final Map<Integer, Long> offsets;
        private CdcTransport.Session session;
        private CdcTransport.Message pending;
        private long nextEmission;

        private SessionConnector(CdcTransport transport, String identity, Set<Integer> partitions, Map<Integer, Long> offsets) {
            this.transport = transport;
            this.identity = identity;
            this.partitions = partitions;
            this.offsets = Map.copyOf(offsets);
        }

        @Override
        public String name() {
            return mapping.datasource();
        }

        @Override
        public Stream<SourceRecord> read(SourceQuery ignored) {
            synchronized (gate) {
                requireOpen();
            }
            if (!identity.equals(transport.identity()) || !partitions.equals(transport.partitions())) {
                throw new IllegalStateException("CDC transport descriptor changed before opening");
            }
            session = Objects.requireNonNull(transport.open(offsets));
            activeSession = session;
            var iterator = new Spliterators.AbstractSpliterator<SourceRecord>(Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL) {
                @Override
                public Spliterator<SourceRecord> trySplit() {
                    return null;
                }

                @Override
                public boolean tryAdvance(Consumer<? super SourceRecord> action) {
                    while (awaitDemand()) {
                        CdcTransport.Message message = session.next();
                        if (message == null) {
                            return false;
                        }
                        if (!partitions.contains(message.partition())) {
                            throw new IllegalArgumentException("CDC message belongs to an unassigned partition");
                        }
                        var decoded = decoder.decode(message);
                        pending = message;
                        if (decoded.tombstone()) {
                            session.acknowledge(message);
                            var previous = stats;
                            stats = new Stats(previous.recordsProcessed(), previous.recordsFailed(), previous.tombstones() + 1,
                                    previous.messagesAcknowledged() + 1, previous.committedOffsets(), previous.lastProcessedAt(), true, "RUNNING");
                            pending = null;
                            continue;
                        }
                        action.accept(decoded.record());
                        return true;
                    }
                    return false;
                }
            };
            return StreamSupport.stream(iterator, false).onClose(() -> {
                try {
                    session.close();
                } finally {
                    activeSession = null;
                }
            });
        }

        private void committed(SourceRecord record) {
            if (pending == null || record.position().sequence() != pending.offset()
                    || !record.position().partition().equals(DebeziumDecoder.partition(pending.topic(), pending.partition()))) {
                throw new IllegalStateException("CDC acknowledgment and committed record disagree");
            }
            var previous = stats;
            var committed = new LinkedHashMap<>(previous.committedOffsets());
            committed.merge(pending.partition(), pending.offset(), Math::max);
            stats = new Stats(previous.recordsProcessed() + 1, previous.recordsFailed(), previous.tombstones(), previous.messagesAcknowledged(),
                    committed, Instant.now(), true, "ACKNOWLEDGING");
            // A failure here leaves the durable receipt intact and forces transport redelivery/recovery.
            session.acknowledge(pending);
            previous = stats;
            stats = new Stats(previous.recordsProcessed(), previous.recordsFailed(), previous.tombstones(), previous.messagesAcknowledged() + 1,
                    previous.committedOffsets(), previous.lastProcessedAt(), true, "RUNNING");
            pending = null;
        }

        private boolean awaitDemand() {
            synchronized (gate) {
                while (!closed) {
                    long delay = mapping.sync().maxRecordsPerSecond() == null ? 0 : nextEmission - System.nanoTime();
                    if (!paused && delay <= 0) {
                        if (mapping.sync().maxRecordsPerSecond() != null) {
                            nextEmission = System.nanoTime() + Math.max(1, 1_000_000_000L / mapping.sync().maxRecordsPerSecond());
                        }
                        return true;
                    }
                    try {
                        if (paused) {
                            gate.wait();
                        } else {
                            gate.wait(delay / 1_000_000, (int) (delay % 1_000_000));
                        }
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("CDC consumer interrupted", failure);
                    }
                }
                return false;
            }
        }
    }
}
