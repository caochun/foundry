package org.openfoundry.foundation.api;

import org.openfoundry.foundation.spi.ObjectRecord;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SearchResult(List<Hit> hits, int totalCount, boolean hasNextPage) {
    public SearchResult { hits = List.copyOf(hits); }

    /** Highlight values are plain original text from visible fields, never generated HTML. */
    public record Hit(ObjectRecord node, double score, Map<String, List<String>> highlights, String cursor) {
        public Hit {
            var copy = new LinkedHashMap<String, List<String>>();
            highlights.forEach((field, snippets) -> copy.put(field, List.copyOf(snippets)));
            highlights = Collections.unmodifiableMap(copy);
        }
    }
}
