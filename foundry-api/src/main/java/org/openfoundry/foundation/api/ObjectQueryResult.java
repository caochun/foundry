package org.openfoundry.foundation.api;

import org.openfoundry.foundation.spi.ObjectRecord;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Counts and cursor positions refer only to authorized, matching objects. */
public record ObjectQueryResult(List<ObjectRecord> items, int totalCount, int offset) {
    public ObjectQueryResult {
        items = List.copyOf(items);
    }

    public Connection connection() {
        var edges = new ArrayList<Edge>();
        for (int index = 0; index < items.size(); index++) {
            edges.add(new Edge(items.get(index), cursor(offset + index)));
        }
        return new Connection(edges, new PageInfo((long) offset + items.size() < totalCount, offset > 0,
                edges.isEmpty() ? null : edges.getFirst().cursor(), edges.isEmpty() ? null : edges.getLast().cursor()), totalCount);
    }

    public static int offsetAfter(String cursor) {
        if (cursor == null) return 0;
        if (cursor.length() > 64) throw new IllegalArgumentException("Invalid query cursor");
        try {
            String decoded = new String(Base64.getDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!decoded.matches("cursor:(0|[1-9][0-9]*)")) throw new IllegalArgumentException("Invalid query cursor");
            int position = Integer.parseInt(decoded.substring(7));
            if (!cursor(position).equals(cursor)) throw new IllegalArgumentException("Invalid query cursor");
            return Math.addExact(position, 1);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid query cursor", invalid);
        }
    }

    private static String cursor(int position) {
        return Base64.getEncoder().encodeToString(("cursor:" + position).getBytes(StandardCharsets.UTF_8));
    }

    public record Edge(ObjectRecord node, String cursor) {}
    public record PageInfo(boolean hasNextPage, boolean hasPreviousPage, String startCursor, String endCursor) {}
    public record Connection(List<Edge> edges, PageInfo pageInfo, int totalCount) {
        public Connection { edges = List.copyOf(edges); }
    }
}
