package org.openfoundry.foundation.sync;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Explicitly assigned Kafka sessions; local durable offsets take precedence over broker group offsets. */
public final class KafkaCdcTransport implements CdcTransport {
    private final String identity;
    private final String topic;
    private final Set<Integer> partitions;
    private final Duration timeout;
    private final Supplier<Consumer<byte[], byte[]>> factory;

    public KafkaCdcTransport(String identity, String topic, Set<Integer> partitions, Map<String, Object> properties, Duration timeout) {
        this(identity, topic, partitions, timeout, factory(properties));
    }

    KafkaCdcTransport(String identity, String topic, Set<Integer> partitions, Duration timeout, Supplier<Consumer<byte[], byte[]>> factory) {
        if (identity == null || identity.isBlank() || topic == null || topic.isBlank() || partitions.isEmpty()
                || partitions.stream().anyMatch(partition -> partition < 0)) {
            throw new IllegalArgumentException("Kafka identity, topic and partitions are required");
        }
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("Kafka timeout must be positive and at most one minute");
        }
        this.identity = identity;
        this.topic = topic;
        this.partitions = Set.copyOf(partitions);
        this.timeout = timeout;
        this.factory = Objects.requireNonNull(factory);
    }

    @Override
    public String identity() {
        return identity;
    }

    @Override
    public Set<Integer> partitions() {
        return partitions;
    }

    @Override
    public Session open(Map<Integer, Long> committedOffsets) {
        if (!partitions.containsAll(committedOffsets.keySet()) || committedOffsets.values().stream().anyMatch(offset -> offset < 0 || offset == Long.MAX_VALUE)) {
            throw new IllegalArgumentException("Invalid committed Kafka offsets");
        }
        var consumer = Objects.requireNonNull(factory.get());
        try {
            var assigned = partitions.stream().map(partition -> new TopicPartition(topic, partition)).toList();
            consumer.assign(assigned);
            // For assignments without local evidence, never trust an unrelated/ahead broker group cursor.
            // Seek to the log beginning explicitly; replay is safer than silently skipping unmaterialized messages.
            consumer.seekToBeginning(assigned.stream().filter(partition -> !committedOffsets.containsKey(partition.partition())).toList());
            committedOffsets.forEach((partition, offset) -> consumer.seek(new TopicPartition(topic, partition), Math.incrementExact(offset)));
            return new KafkaSession(consumer);
        } catch (RuntimeException | Error failure) {
            try {
                consumer.close(timeout);
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private final class KafkaSession implements Session {
        private final Consumer<byte[], byte[]> consumer;
        private final ArrayDeque<ConsumerRecord<byte[], byte[]>> buffered = new ArrayDeque<>();
        private Message pending;
        private boolean closed;

        private KafkaSession(Consumer<byte[], byte[]> consumer) {
            this.consumer = consumer;
        }

        @Override
        public Message next() {
            requireOpen();
            if (pending != null) {
                throw new IllegalStateException("Acknowledge the current Kafka message before pulling another");
            }
            if (buffered.isEmpty()) {
                consumer.poll(timeout).forEach(buffered::addLast);
            }
            if (buffered.isEmpty()) {
                return null;
            }
            var record = buffered.removeFirst();
            pending = new Message(record.topic(), record.partition(), record.offset(), record.timestamp() < 0 ? null : Instant.ofEpochMilli(record.timestamp()),
                    utf8(record.key()), utf8(record.value()));
            return pending;
        }

        @Override
        public void acknowledge(Message message) {
            requireOpen();
            if (pending == null || !pending.equals(message)) {
                throw new IllegalArgumentException("Kafka acknowledgment is not the current delivered message");
            }
            consumer.commitSync(Map.of(new TopicPartition(message.topic(), message.partition()),
                    new OffsetAndMetadata(Math.incrementExact(message.offset()))), timeout);
            pending = null;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                buffered.clear();
                consumer.close(timeout);
            }
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException("Kafka CDC session is closed");
            }
        }
    }

    private static Supplier<Consumer<byte[], byte[]>> factory(Map<String, Object> properties) {
        var config = new LinkedHashMap<>(properties);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        config.putIfAbsent(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);
        config.putIfAbsent(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, 8_000_000);
        config.putIfAbsent(ConsumerConfig.FETCH_MAX_BYTES_CONFIG, 16_000_000);
        config.putIfAbsent(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 30_000);
        return () -> new KafkaConsumer<>(config);
    }

    private static String utf8(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("CDC transport requires valid UTF-8 JSON");
        }
    }
}
