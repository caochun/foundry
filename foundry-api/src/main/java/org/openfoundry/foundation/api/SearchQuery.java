package org.openfoundry.foundation.api;

import org.openfoundry.foundation.spi.QueryOptions;
import org.openfoundry.foundation.spi.schema.PropertyValues;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Search modes make the upstream memory/PostgreSQL matching difference explicit. */
public record SearchQuery(String query, List<String> fields, Map<String, Object> filter, Mode mode,
                          int limit, int offset, Instant asOfValidTime, Instant asOfRecordedTime,
                          boolean includeDeleted) {
    private static final Pattern SPACE = Pattern.compile("[\\s\\uFEFF]+", Pattern.UNICODE_CHARACTER_CLASS);

    public SearchQuery(String query, List<String> fields, Map<String, Object> filter) {
        this(query, fields, filter, Mode.TERMS, 20, 0, null, null, false);
    }

    public SearchQuery {
        if (query == null || query.length() > 4096 || terms(query).isEmpty() || terms(query).size() > 256) {
            throw new IllegalArgumentException("Search requires nonblank text with at most 4096 characters and 256 terms");
        }
        if (fields != null) {
            if (fields.stream().anyMatch(field -> field == null || field.isBlank())) throw new IllegalArgumentException("Invalid search field");
            if (Set.copyOf(fields).size() != fields.size()) throw new IllegalArgumentException("Duplicate search field");
            fields = List.copyOf(fields);
        }
        if (filter == null || mode == null) throw new IllegalArgumentException("Search filter and mode must not be null");
        filter = PropertyValues.immutableMap(filter);
        if (limit < 0 || offset < 0) throw new IllegalArgumentException("Search limit and offset must not be negative");
        if ((asOfValidTime == null) != (asOfRecordedTime == null)) throw new IllegalArgumentException("Temporal search requires both times");
    }

    public enum Mode { TERMS, PHRASE }

    public QueryOptions sourceView() {
        return new QueryOptions(Integer.MAX_VALUE, 0, asOfValidTime, asOfRecordedTime, includeDeleted);
    }

    static List<String> terms(String value) {
        return Arrays.stream(SPACE.split(value.toLowerCase(Locale.ROOT))).filter(term -> !term.isEmpty()).toList();
    }

    public static SearchQuery fromJson(Map<String, Object> input) {
        if (input == null || !Set.of("query", "fields", "filter", "mode", "limit", "first", "offset", "after", "asOfValidTime", "asOfRecordedTime", "includeDeleted").containsAll(input.keySet())) {
            throw new IllegalArgumentException("Unknown search option");
        }
        if (input.containsKey("limit") && input.containsKey("first")) throw new IllegalArgumentException("Use limit or first, not both");
        List<String> fields = null;
        if (input.get("fields") != null) {
            if (!(input.get("fields") instanceof List<?> list)) throw new IllegalArgumentException("Search fields must be a list");
            fields = list.stream().map(SearchQuery::text).toList();
        }
        var filter = new LinkedHashMap<String, Object>();
        if (input.get("filter") != null) {
            if (!(input.get("filter") instanceof Map<?, ?> map)) throw new IllegalArgumentException("Search filter must be an object");
            map.forEach((key, value) -> filter.put(text(key), value));
        }
        int offset = integer(input.get("offset"), 0);
        if (input.get("after") != null) {
            if (offset != 0) throw new IllegalArgumentException("after and offset cannot be combined");
            offset = ObjectQueryResult.offsetAfter(text(input.get("after")));
        }
        Object deleted = input.getOrDefault("includeDeleted", false);
        if (!(deleted instanceof Boolean)) throw new IllegalArgumentException("includeDeleted requires a boolean");
        return new SearchQuery(text(input.get("query")), fields, filter,
                input.get("mode") == null ? Mode.TERMS : Mode.valueOf(text(input.get("mode")).toUpperCase(Locale.ROOT)),
                integer(input.containsKey("limit") ? input.get("limit") : input.get("first"), 20), offset,
                instant(input.get("asOfValidTime")), instant(input.get("asOfRecordedTime")), (Boolean) deleted);
    }

    public static SearchQuery fromParameters(Map<String, String> parameters) {
        if (!Set.of("q", "fields", "mode", "limit", "offset", "after", "asOfValidTime", "asOfRecordedTime", "includeDeleted").containsAll(parameters.keySet())) {
            throw new IllegalArgumentException("Unknown search parameter");
        }
        var input = new LinkedHashMap<String, Object>();
        input.put("query", parameters.get("q"));
        for (String name : List.of("mode", "after", "asOfValidTime", "asOfRecordedTime")) {
            if (parameters.containsKey(name)) input.put(name, parameters.get(name));
        }
        for (String name : List.of("limit", "offset")) {
            if (parameters.containsKey(name)) input.put(name, Integer.parseInt(parameters.get(name)));
        }
        if (parameters.containsKey("fields")) {
            String fields = parameters.get("fields");
            input.put("fields", fields.isEmpty() ? List.of() : Arrays.stream(fields.split(",", -1)).map(String::strip).toList());
        }
        if (parameters.containsKey("includeDeleted")) {
            String value = parameters.get("includeDeleted");
            if (!Set.of("true", "false").contains(value)) throw new IllegalArgumentException("includeDeleted requires true or false");
            input.put("includeDeleted", Boolean.valueOf(value));
        }
        return fromJson(input);
    }

    private static String text(Object value) {
        if (!(value instanceof String text)) throw new IllegalArgumentException("Search option requires text");
        return text;
    }

    private static int integer(Object value, int fallback) {
        if (value == null) return fallback;
        if (!(value instanceof Integer number)) throw new IllegalArgumentException("Search pagination requires integers");
        return number;
    }

    private static Instant instant(Object value) {
        if (value == null) return null;
        try { return Instant.parse(text(value)); }
        catch (java.time.format.DateTimeParseException invalid) { throw new IllegalArgumentException("Invalid search time", invalid); }
    }
}
