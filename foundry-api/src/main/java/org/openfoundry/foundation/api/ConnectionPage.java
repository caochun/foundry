package org.openfoundry.foundation.api;

/** Cursor positions refer to the full authorized, filtered and ordered object sequence. */
public record ConnectionPage(Integer first, String after, Integer last, String before, int offset) {
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public ConnectionPage {
        if ((first != null && first < 0) || (last != null && last < 0) || offset < 0) {
            throw new IllegalArgumentException("Connection sizes and offset must not be negative");
        }
        if (first != null && last != null) throw new IllegalArgumentException("Use first or last, not both");
        if (offset != 0 && (after != null || before != null || last != null)) {
            throw new IllegalArgumentException("Offset cannot be combined with cursors or backward pagination");
        }
        if (after != null) ObjectQueryResult.offsetAfter(after);
        if (before != null) ObjectQueryResult.offsetAfter(before);
    }

    public static ConnectionPage defaults() {
        return new ConnectionPage(null, null, null, null, 0);
    }

    Window window(int totalCount) {
        if (totalCount < 0) throw new IllegalArgumentException("Negative object count");
        int lower = Math.min(totalCount, after == null ? offset : ObjectQueryResult.offsetAfter(after));
        int upper = before == null ? totalCount : Math.min(totalCount, ObjectQueryResult.offsetAfter(before) - 1);
        upper = Math.max(lower, upper);
        boolean backward = last != null || (before != null && first == null);
        int size = backward ? (last == null ? DEFAULT_SIZE : Math.min(MAX_SIZE, last))
                : (first == null ? DEFAULT_SIZE : Math.min(MAX_SIZE, first));
        if (backward) lower = Math.max(lower, upper - size);
        else upper = (int) Math.min(upper, (long) lower + size);
        return new Window(lower, upper);
    }

    record Window(int start, int end) {}
}
