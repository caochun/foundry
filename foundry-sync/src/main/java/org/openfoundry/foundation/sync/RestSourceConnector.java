package org.openfoundry.foundation.sync;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openfoundry.foundation.spi.LineageValues;
import org.openfoundry.foundation.spi.Provenance;
import org.openfoundry.foundation.spi.schema.PropertyValues;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** Paginated HTTP JSON. Incremental mode requires an explicitly declared ordered change-feed protocol. */
public final class RestSourceConnector implements ManagedConnector {
    public static final String VERSION = "0.1.0";
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(100).maxStringLength(1_000_000).maxNumberLength(1000).build()).build())
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final String name;
    private final String sourceSystem;
    private final HttpClient client;
    private final Map<String, String> headers;
    private final String sourceIdentity;
    private final Object gate = new Object();
    private final Set<Extraction> extractions = ConcurrentHashMap.newKeySet();
    private volatile boolean ready;
    private volatile boolean closed;
    private boolean paused;
    private URI endpoint;
    private Settings settings;
    private String signature;

    public RestSourceConnector(String name, String sourceSystem) {
        this(name, sourceSystem, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), Map.of(), sourceSystem);
    }

    /** Client and credentials belong to the host. sourceIdentity must change when endpoint/account data scope changes. */
    public RestSourceConnector(String name, String sourceSystem, HttpClient client, Map<String, String> headers, String sourceIdentity) {
        if (name == null || name.isBlank() || sourceSystem == null || sourceSystem.isBlank() || sourceIdentity == null || sourceIdentity.isBlank()) {
            throw new IllegalArgumentException("Connector, source and stable source identity are required");
        }
        this.name = name;
        this.sourceSystem = sourceSystem;
        this.client = Objects.requireNonNull(client);
        if (client.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("Managed REST extraction requires redirects disabled");
        }
        this.headers = Map.copyOf(headers);
        this.sourceIdentity = sourceIdentity;
    }

    @Override public String name() { return name; }
    @Override public String version() { return VERSION; }
    @Override public String partition() { return "rest-poll-v1"; }
    @Override public Capabilities capabilities() { return new Capabilities(false, true, ready && settings.incremental(), false); }

    @Override
    public void initialize(DatasourceMapping.Connection configuration) {
        synchronized (gate) {
            if (ready || closed) throw new IllegalStateException("REST connector already initialized or closed");
            endpoint = URI.create(configuration.url());
            if (!Set.of("http", "https").contains(endpoint.getScheme()) || endpoint.getHost() == null
                    || endpoint.getUserInfo() != null || endpoint.getFragment() != null) {
                throw new IllegalArgumentException("REST endpoint must be an HTTP(S) URL without user info or fragment");
            }
            settings = Settings.from(configuration.properties());
            if (settings.dataField().equals(settings.nextField())) throw new IllegalArgumentException("REST data and next fields must differ");
            if (endpoint.getRawQuery() != null) {
                for (String parameter : endpoint.getRawQuery().split("&")) {
                    String key = java.net.URLDecoder.decode(parameter.split("=", 2)[0], StandardCharsets.UTF_8);
                    if (key.equals(settings.pageParam()) || key.equals(settings.limitParam()) || key.equals(settings.sinceParam())) {
                        throw new IllegalArgumentException("REST endpoint already contains a reserved pagination parameter");
                    }
                }
            }
            signature = LineageValues.hash(true, List.of("rest-source-v1", name, sourceSystem, sourceIdentity,
                    endpoint.toASCIIString(), configuration.table(), configuration.properties()));
            ready = true;
        }
    }

    @Override
    public Health healthCheck() {
        if (!ready || closed) return new Health(false, "rest", 0, closed ? "CLOSED" : "NEW");
        long started = System.nanoTime();
        try (var extraction = new Extraction(false, null, ExtractOptions.defaults())) {
            var response = extraction.send(request(endpoint, Duration.ofSeconds(10), "HEAD"));
            boolean healthy = response.statusCode() / 100 == 2 && !closed;
            return new Health(healthy, "rest", (System.nanoTime() - started) / 1_000_000, "HTTP_" + response.statusCode());
        } catch (RuntimeException failure) {
            return new Health(false, "rest", (System.nanoTime() - started) / 1_000_000, "UNAVAILABLE");
        }
    }

    @Override public SourceSchema discoverSchema() {
        requireReady();
        throw new UnsupportedOperationException("REST schema discovery needs an explicit API schema adapter");
    }
    @Override public Stream<SourceRecord> read(SourceQuery query) {
        throw new UnsupportedOperationException("Use the configured fullExtract/incrementalExtract endpoint");
    }
    @Override public Stream<SourceRecord> fullExtract(ExtractOptions options) { return extract(false, null, options); }
    @Override public Stream<SourceRecord> incrementalExtract(Cursor since, ExtractOptions options) {
        requireReady();
        if (!settings.incremental()) throw new UnsupportedOperationException("REST incremental fields must be declared explicitly");
        return extract(true, since, options);
    }

    private Stream<SourceRecord> extract(boolean incremental, Cursor since, ExtractOptions options) {
        synchronized (gate) {
            requireReady();
            var extraction = new Extraction(incremental, since, Objects.requireNonNull(options));
            extractions.add(extraction);
            return StreamSupport.stream(extraction, false).onClose(extraction::close);
        }
    }

    @Override public void pause() { synchronized (gate) { requireReady(); paused = true; } }
    @Override public void resume() { synchronized (gate) { requireReady(); paused = false; gate.notifyAll(); } }
    @Override public void close() {
        synchronized (gate) { closed = true; gate.notifyAll(); }
        extractions.forEach(Extraction::close);
    }
    private void requireReady() { if (!ready || closed) throw new IllegalStateException("REST connector is not ready"); }

    private final class Extraction extends Spliterators.AbstractSpliterator<SourceRecord> implements AutoCloseable {
        private final boolean incremental;
        private final ExtractOptions options;
        private final String since;
        private final Set<String> pages = new HashSet<>();
        private List<Map<String, Object>> rows = List.of();
        private int index;
        private boolean fetched;
        private String nextPage;
        private long lastSequence = -1;
        private String lastCursor;
        private long nextEmission;
        private volatile boolean stopped;
        private volatile CompletableFuture<?> pending;

        Extraction(boolean incremental, Cursor cursor, ExtractOptions options) {
            super(Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL);
            this.incremental = incremental;
            this.options = options;
            if (cursor == null) {
                since = null;
            } else {
                if (!(cursor.token() instanceof Map<?, ?> token) || !token.keySet().equals(Set.of("format", "signature", "cursor", "sequence"))
                        || !"rest-event-v1".equals(token.get("format")) || !signature.equals(token.get("signature"))
                        || !(token.get("cursor") instanceof String value) || value.isBlank() || value.length() > 8192
                        || integer(token.get("sequence")) != cursor.sequence()) {
                    throw new IllegalArgumentException("Invalid or incompatible REST checkpoint");
                }
                since = value;
                lastCursor = value;
                lastSequence = cursor.sequence();
            }
        }

        @Override public Spliterator<SourceRecord> trySplit() { return null; }
        @Override public boolean tryAdvance(Consumer<? super SourceRecord> action) {
            if (stopped) return false;
            try {
                while (index == rows.size()) {
                    if (fetched && nextPage == null) { close(); return false; }
                    awaitDemand(false);
                    fetch();
                }
                awaitDemand(true);
                var row = rows.get(index++);
                String id = RecordMapper.canonicalId(row.get(settings.idField()));
                Instant observed = Instant.now();
                if (!incremental) {
                    action.accept(new SourceRecord(sourceSystem, id, "UPSERT", observed, row,
                            new Provenance(sourceSystem, id, null, "rest-snapshot", observed, name, null)));
                    return true;
                }
                long sequence = integer(row.get(settings.sequenceField()));
                String eventId = text(row, settings.eventIdField());
                String cursor = text(row, settings.cursorField());
                if (sequence <= lastSequence || cursor.equals(lastCursor) || cursor.length() > 8192) {
                    throw new IllegalArgumentException("REST change-feed positions must strictly advance");
                }
                Instant produced = Instant.parse(text(row, settings.timestampField()));
                String operation = settings.operationField() == null ? "UPSERT" : text(row, settings.operationField());
                var token = Map.<String, Object>of("format", "rest-event-v1", "signature", signature, "cursor", cursor, "sequence", sequence);
                var position = new SourcePosition(partition(), eventId, sequence, token);
                var provenance = new Provenance(sourceSystem, id, eventId, "rest-event-v1", produced, name, LineageValues.hash(true, row));
                var record = new SourceRecord(sourceSystem, id, operation, observed, row, provenance, position);
                lastSequence = sequence;
                lastCursor = cursor;
                action.accept(record);
                return true;
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        private void fetch() {
            if (pages.size() >= settings.maxPages()) throw new IllegalStateException("REST page budget exceeded");
            String pageKey = nextPage == null ? "initial" : "cursor:" + nextPage;
            if (!pages.add(pageKey)) throw new IllegalStateException("REST pagination cycle detected");
            var response = send(request(pageUri(nextPage, since, options.batchSize()), options.queryTimeout(), "GET"));
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("REST source returned HTTP " + response.statusCode());
            String contentType = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].strip().toLowerCase(java.util.Locale.ROOT);
            if (!(contentType.equals("application/json") || contentType.startsWith("application/") && contentType.endsWith("+json"))) {
                throw new IllegalArgumentException("REST source must return JSON content type");
            }
            try {
                String body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.body())).toString();
                Object parsed = JSON.readValue(body, Object.class);
                Object data = parsed;
                String next = null;
                if (parsed instanceof Map<?, ?>) {
                    Map<String, Object> object = object(parsed);
                    if (object.containsKey(settings.dataField())) {
                        data = object.get(settings.dataField());
                        if (!(data instanceof List<?>)) throw new IllegalArgumentException("REST data field must be an array");
                        if (object.get(settings.nextField()) != null) next = text(object, settings.nextField());
                    } else {
                        if (object.containsKey(settings.nextField())) throw new IllegalArgumentException("REST page metadata has no data field");
                        data = List.of(object);
                    }
                }
                if (!(data instanceof List<?> list) || list.size() > options.batchSize()) {
                    throw new IllegalArgumentException("REST response exceeds requested batch size or is not a row collection");
                }
                if (next != null && next.length() > 8192) throw new IllegalArgumentException("REST page cursor is too long");
                var result = new ArrayList<Map<String, Object>>();
                for (Object value : list) result.add(PropertyValues.immutableMap(object(value)));
                rows = List.copyOf(result);
                index = 0;
                nextPage = next;
                fetched = true;
            } catch (java.io.IOException failure) {
                throw new IllegalArgumentException("Invalid REST UTF-8 JSON");
            }
        }

        private HttpResponse<byte[]> send(HttpRequest request) {
            synchronized (gate) {
                requireReady();
                if (stopped) throw new IllegalStateException("REST extraction is closed");
                extractions.add(this);
                pending = client.sendAsync(request, info -> new LimitedBody(settings.maxResponseBytes()));
            }
            try {
                @SuppressWarnings("unchecked")
                var future = (CompletableFuture<HttpResponse<byte[]>>) pending;
                var response = future.get(request.timeout().orElseThrow().toMillis(), TimeUnit.MILLISECONDS);
                if (closed || stopped) throw new IllegalStateException("REST extraction is closed");
                return response;
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                pending.cancel(true);
                throw new IllegalStateException("REST request interrupted");
            } catch (Exception failure) {
                pending.cancel(true);
                throw new IllegalStateException("REST request failed or exceeded its time/size budget");
            } finally {
                pending = null;
            }
        }

        private void awaitDemand(boolean emission) {
            synchronized (gate) {
                while (true) {
                    requireReady();
                    if (stopped) throw new IllegalStateException("REST extraction is closed");
                    long delay = emission && options.maxRecordsPerSecond() != null ? nextEmission - System.nanoTime() : 0;
                    if (!paused && delay <= 0) {
                        if (emission && options.maxRecordsPerSecond() != null) nextEmission = System.nanoTime() + Math.max(1, 1_000_000_000L / options.maxRecordsPerSecond());
                        return;
                    }
                    try {
                        if (paused) gate.wait();
                        else gate.wait(delay / 1_000_000, (int) (delay % 1_000_000));
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("REST extraction interrupted");
                    }
                }
            }
        }

        @Override public void close() {
            synchronized (gate) {
                stopped = true;
                if (pending != null) pending.cancel(true);
                extractions.remove(this);
                gate.notifyAll();
            }
        }
    }

    private URI pageUri(String page, String since, int batchSize) {
        var query = new ArrayList<String>();
        if (endpoint.getRawQuery() != null) query.add(endpoint.getRawQuery());
        query.add(enc(settings.limitParam()) + "=" + batchSize);
        if (page != null) query.add(enc(settings.pageParam()) + "=" + enc(page));
        if (since != null) query.add(enc(settings.sinceParam()) + "=" + enc(since));
        String base = endpoint.toASCIIString().split("\\?", 2)[0];
        return URI.create(base + "?" + String.join("&", query));
    }

    private HttpRequest request(URI uri, Duration timeout, String method) {
        var builder = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json");
        headers.forEach(builder::header);
        return builder.method(method, HttpRequest.BodyPublishers.noBody()).build();
    }
    private static String enc(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8); }
    private static long integer(Object value) {
        if (!(value instanceof Number number)) throw new IllegalArgumentException("REST position must be an exact non-negative integer");
        try {
            long result = new BigDecimal(number.toString()).longValueExact();
            if (result < 0) throw new IllegalArgumentException("Negative REST position");
            return result;
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("REST position must fit a non-negative 64-bit integer");
        }
    }
    private static String text(Map<String, Object> values, String key) {
        if (!(values.get(key) instanceof String text) || text.isBlank()) throw new IllegalArgumentException("Missing or invalid REST field: " + key);
        return text;
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String))) throw new IllegalArgumentException("REST row must be an object");
        return (Map<String, Object>) map;
    }

    private record Settings(String idField, String dataField, String nextField, String pageParam, String limitParam,
                            String sinceParam, String cursorField, String sequenceField, String eventIdField,
                            String timestampField, String operationField, int maxPages, int maxResponseBytes) {
        boolean incremental() { return sinceParam != null; }
        static Settings from(Map<String, Object> raw) {
            Set<String> known = Set.of("idField", "dataField", "nextField", "pageParam", "limitParam", "sinceParam", "cursorField",
                    "sequenceField", "eventIdField", "timestampField", "operationField", "maxPages", "maxResponseBytes");
            if (!known.containsAll(raw.keySet())) throw new IllegalArgumentException("Unknown managed REST option");
            var fields = new LinkedHashMap<String, String>();
            for (String name : List.of("sinceParam", "cursorField", "sequenceField", "eventIdField", "timestampField")) {
                if (raw.containsKey(name)) fields.put(name, text(raw, name));
            }
            if (!fields.isEmpty() && fields.size() != 5) throw new IllegalArgumentException("All five REST incremental options are required");
            if (raw.containsKey("operationField") && fields.isEmpty()) throw new IllegalArgumentException("REST operationField requires incremental mode");
            String page = option(raw, "pageParam", "cursor");
            String limit = option(raw, "limitParam", "limit");
            if (page.equals(limit) || page.equals(fields.get("sinceParam")) || limit.equals(fields.get("sinceParam"))) throw new IllegalArgumentException("Ambiguous REST query options");
            int pages = Math.toIntExact(integer(raw.getOrDefault("maxPages", 1000)));
            int bytes = Math.toIntExact(integer(raw.getOrDefault("maxResponseBytes", 8_000_000)));
            if (pages < 1 || pages > 100_000 || bytes < 1 || bytes > 32_000_000) throw new IllegalArgumentException("Invalid REST resource budget");
            return new Settings(option(raw, "idField", "id"), option(raw, "dataField", "data"), option(raw, "nextField", "next"), page, limit,
                    fields.get("sinceParam"), fields.get("cursorField"), fields.get("sequenceField"), fields.get("eventIdField"), fields.get("timestampField"),
                    raw.containsKey("operationField") ? text(raw, "operationField") : null, pages, bytes);
        }
        private static String option(Map<String, Object> raw, String key, String fallback) { return raw.containsKey(key) ? text(raw, key) : fallback; }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final int limit;
        private Flow.Subscription subscription;
        private long received;
        LimitedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; delegate.onSubscribe(subscription); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) received += buffer.remaining();
            if (received > limit) {
                subscription.cancel();
                delegate.onError(new IllegalStateException("REST response exceeds byte budget"));
            } else delegate.onNext(buffers);
        }
        @Override public void onError(Throwable failure) { delegate.onError(failure); }
        @Override public void onComplete() { delegate.onComplete(); }
    }
}
