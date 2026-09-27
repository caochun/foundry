package org.openfoundry.foundation.api;

import org.openfoundry.foundation.spi.QueryOptions;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Connection pagination is independent of the trusted storage scan's QueryOptions. */
public record ObjectConnectionQuery(Map<String, Object> filter, Map<String, String> orderBy,
                                    ConnectionPage page, Instant asOfValidTime, Instant asOfRecordedTime,
                                    boolean includeDeleted) {
    public ObjectConnectionQuery(Map<String, Object> filter, Map<String, String> orderBy, ConnectionPage page) {
        this(filter, orderBy, page, null, null, false);
    }

    public ObjectConnectionQuery {
        if (page == null) throw new IllegalArgumentException("Connection page is required");
        var validated = new ObjectQuery(filter, orderBy, new QueryOptions(Integer.MAX_VALUE, 0, asOfValidTime, asOfRecordedTime, includeDeleted));
        filter = validated.filter();
        orderBy = validated.orderBy();
    }

    ObjectQuery sourceQuery() {
        return new ObjectQuery(filter, orderBy, new QueryOptions(Integer.MAX_VALUE, 0, asOfValidTime, asOfRecordedTime, includeDeleted));
    }

    public static ObjectConnectionQuery fromJson(Map<String, Object> input) {
        if (input == null || !Set.of("filter", "orderBy", "first", "last", "before", "after", "offset", "asOfValidTime", "asOfRecordedTime", "includeDeleted").containsAll(input.keySet())) {
            throw new IllegalArgumentException("Unknown connection option");
        }
        var offset = integer(input.get("offset"));
        var page = new ConnectionPage(integer(input.get("first")), cursor(input.get("after")), integer(input.get("last")),
                cursor(input.get("before")), offset == null ? 0 : offset);
        var source = new LinkedHashMap<>(input);
        source.remove("last");
        source.remove("before");
        source.remove("after");
        source.put("first", Integer.MAX_VALUE);
        source.put("offset", 0);
        var query = ObjectQuery.fromJson(source);
        return new ObjectConnectionQuery(query.filter(), query.orderBy(), page, query.options().asOfValidTime(),
                query.options().asOfRecordedTime(), query.options().includeDeleted());
    }

    private static Integer integer(Object value) {
        if (value == null) return null;
        if (!(value instanceof Integer number)) throw new IllegalArgumentException("Connection size and offset must be integers");
        return number;
    }

    private static String cursor(Object value) {
        if (value == null) return null;
        if (!(value instanceof String text)) throw new IllegalArgumentException("Cursor must be a string");
        return text;
    }
}
