package org.openfoundry.foundation.api;

import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.spi.schema.PropertyDefinition;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Search scores, highlights and page counts are derived only from visible fields and rows. */
final class ObjectSearchPlan {
    private final SearchQuery query;
    private final List<PropertyDefinition> fields;
    private final List<String> terms;
    private final String phrase;

    ObjectSearchPlan(OntologySchema schema, List<PropertyDefinition> definitions, Set<String> visible, SearchQuery query) {
        this.query = query;
        this.terms = SearchQuery.terms(query.query());
        this.phrase = query.query().toLowerCase(Locale.ROOT);
        if (query.fields() == null) {
            fields = definitions.stream().filter(field -> !field.primary() && visible.contains(field.name()) && searchable(schema, field)).toList();
        } else {
            var declared = new LinkedHashMap<String, PropertyDefinition>();
            definitions.forEach(field -> declared.put(field.name(), field));
            fields = query.fields().stream().map(name -> {
                var field = declared.get(name);
                if (field == null) throw new IllegalArgumentException("Unknown search field: " + name);
                if (!field.primary() && !visible.contains(name)) throw new SecurityException("Search field is not visible");
                if (!searchable(schema, field)) throw new IllegalArgumentException("Search field must have a textual scalar or enum type");
                return field;
            }).toList();
        }
    }

    SearchResult evaluate(List<ObjectRecord> rows, Function<ObjectRecord, ObjectRecord> project) {
        var matching = rows.stream().map(this::match).filter(hit -> hit.score() > 0)
                .sorted(Comparator.comparingDouble(Match::score).reversed().thenComparing(hit -> hit.object().id())).toList();
        var page = matching.stream().skip(query.offset()).limit(query.limit()).toList();
        var hits = new java.util.ArrayList<SearchResult.Hit>();
        for (int index = 0; index < page.size(); index++) {
            var hit = page.get(index);
            hits.add(new SearchResult.Hit(project.apply(hit.object()), hit.score(), hit.highlights(), ObjectQueryResult.cursor(query.offset() + index)));
        }
        return new SearchResult(hits, matching.size(), (long) query.offset() + hits.size() < matching.size());
    }

    private Match match(ObjectRecord object) {
        double score = 0;
        var highlights = new LinkedHashMap<String, List<String>>();
        for (var field : fields) {
            Object value = field.primary() ? object.id() : object.properties().get(field.name());
            if (value == null) continue;
            if (!(value instanceof String original)) throw new IllegalArgumentException("Stored search value does not match its declared type");
            String text = original.toLowerCase(Locale.ROOT);
            double fieldScore = 0;
            if (query.mode() == SearchQuery.Mode.PHRASE) {
                if (text.contains(phrase)) fieldScore = 1;
            } else {
                for (String term : terms) {
                    int offset = 0;
                    int position;
                    while ((position = text.indexOf(term, offset)) >= 0) {
                        fieldScore++;
                        offset = position + term.length();
                    }
                }
            }
            if (fieldScore > 0) {
                score += fieldScore;
                // Multiple matching terms do not duplicate the same original field text.
                highlights.put(field.name(), List.of(original));
            }
        }
        return new Match(object, score, highlights);
    }

    private static boolean searchable(OntologySchema schema, PropertyDefinition field) {
        return Set.of("String", "ID", "Date", "DateTime", "Duration", "URI").contains(field.type()) || schema.enums().containsKey(field.type());
    }

    private record Match(ObjectRecord object, double score, Map<String, List<String>> highlights) {}
}
