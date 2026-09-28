package org.openfoundry.foundation.spi;

/** Newest first; beforeSequence provides stable pagination over append-only evidence. */
public record LineageQuery(String field, int limit, Long beforeSequence) {
    public LineageQuery {
        if (field != null && !field.matches("[A-Za-z][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid lineage field");
        if (limit < 0 || limit > 1000) throw new IllegalArgumentException("Lineage limit must be between 0 and 1000");
        if (beforeSequence != null && beforeSequence < 1) throw new IllegalArgumentException("Invalid lineage cursor");
    }

    public static LineageQuery defaults() { return new LineageQuery(null, 100, null); }
}
