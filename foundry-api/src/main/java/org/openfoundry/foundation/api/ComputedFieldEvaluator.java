package org.openfoundry.foundation.api;

import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.*;

import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Trusted countLinks evaluator. API callers supply a visibility predicate; direct use has SPI-level authority. */
public final class ComputedFieldEvaluator {
    private final SchemaBoundStorage storage;
    private final OntologySchema schema;
    private final Map<String, Map<String, ComputedFieldDefinition>> fields;

    public ComputedFieldEvaluator(StorageProvider storage, OntologySchema schema) {
        PropertyValues.requireSchema(schema);
        this.storage = SchemaBoundStorage.bind(storage, schema);
        this.schema = schema;
        fields = schema.objectTypes().stream().collect(Collectors.toUnmodifiableMap(ObjectTypeDefinition::name,
                type -> type.computedFields().stream().collect(Collectors.toUnmodifiableMap(ComputedFieldDefinition::name, field -> field))));
    }

    public Object evaluate(RequestContext context, EntityKey source, String field) {
        return evaluate(context, source, field, QueryOptions.defaults(), link -> true);
    }

    public Object evaluate(RequestContext context, EntityKey source, String name, QueryOptions view, Predicate<LinkRecord> visible) {
        return storage.read(context, () -> {
            var field = fields.getOrDefault(source.type(), Map.of()).get(name);
            if (field == null) throw new IllegalArgumentException("Unknown computed field");
            long count = 0;
            int offset = 0;
            while (true) {
                var page = storage.getLinks(context, source, field.linkType(), field.direction(),
                        new QueryOptions(100, offset, view.asOfValidTime(), view.asOfRecordedTime(), false));
                for (var link : page) if (visible.test(link)) count = Math.addExact(count, 1);
                if (page.size() < 100) break;
                offset = Math.addExact(offset, page.size());
            }
            return PropertyValues.normalize(schema, field.type(), Math.toIntExact(count), field.name());
        });
    }
}
