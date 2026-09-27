package org.openfoundry.foundation.schema;

import org.openfoundry.foundation.spi.schema.ActionTypeDefinition;
import org.openfoundry.foundation.spi.schema.LinkTypeDefinition;
import org.openfoundry.foundation.spi.schema.ObjectTypeDefinition;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.spi.schema.PropertyDefinition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Computes a conservative migration diff between two immutable schema snapshots. */
public final class SchemaDiffer {
    public SchemaDiff diff(OntologySchema previous, OntologySchema next) {
        List<SchemaChange> changes = new ArrayList<>();
        for (var entry : next.enums().entrySet()) {
            var old = previous.enums().get(entry.getKey());
            if (old == null) changes.add(new SchemaChange("enum." + entry.getKey(), "enum added", MigrationClass.SAFE));
            else if (!new HashSet<>(old).equals(new HashSet<>(entry.getValue()))) changes.add(new SchemaChange("enum." + entry.getKey(), "enum members changed",
                    entry.getValue().containsAll(old) ? MigrationClass.COMPATIBLE : MigrationClass.BREAKING));
        }
        for (String name : previous.enums().keySet()) if (!next.enums().containsKey(name)) changes.add(new SchemaChange("enum." + name, "enum removed", MigrationClass.BREAKING));
        compareObjects(previous, next, changes);
        compareLinks(previous, next, changes);
        compareActions(previous, next, changes);
        return new SchemaDiff(changes);
    }

    private void compareObjects(OntologySchema previous, OntologySchema next, List<SchemaChange> changes) {
        Map<String, ObjectTypeDefinition> oldTypes = indexObjects(previous);
        Map<String, ObjectTypeDefinition> newTypes = indexObjects(next);
        for (String name : newTypes.keySet()) {
            if (!oldTypes.containsKey(name)) changes.add(new SchemaChange("object." + name, "object type added", MigrationClass.SAFE));
            else compareProperties("object." + name, oldTypes.get(name).properties(), newTypes.get(name).properties(), changes);
        }
        for (String name : oldTypes.keySet()) {
            if (!newTypes.containsKey(name)) changes.add(new SchemaChange("object." + name, "object type removed", MigrationClass.BREAKING));
        }
    }

    private void compareLinks(OntologySchema previous, OntologySchema next, List<SchemaChange> changes) {
        Map<String, LinkTypeDefinition> oldTypes = previous.linkTypes().stream().collect(HashMap::new,
                (map, value) -> map.put(value.name(), value), Map::putAll);
        Map<String, LinkTypeDefinition> newTypes = next.linkTypes().stream().collect(HashMap::new,
                (map, value) -> map.put(value.name(), value), Map::putAll);
        for (String name : newTypes.keySet()) {
            LinkTypeDefinition current = newTypes.get(name);
            LinkTypeDefinition old = oldTypes.get(name);
            if (old == null) {
                changes.add(new SchemaChange("link." + name, "link type added", MigrationClass.SAFE));
                continue;
            }
            if (!old.fromType().equals(current.fromType()) || !old.toType().equals(current.toType())
                    || old.cardinality() != current.cardinality()) {
                changes.add(new SchemaChange("link." + name, "relationship endpoints or cardinality changed", MigrationClass.BREAKING));
            }
            compareProperties("link." + name, old.properties(), current.properties(), changes);
        }
        for (String name : oldTypes.keySet()) {
            if (!newTypes.containsKey(name)) changes.add(new SchemaChange("link." + name, "link type removed", MigrationClass.BREAKING));
        }
    }

    private void compareActions(OntologySchema previous, OntologySchema next, List<SchemaChange> changes) {
        Map<String, ActionTypeDefinition> oldTypes = previous.actionTypes().stream().collect(HashMap::new,
                (map, value) -> map.put(value.name(), value), Map::putAll);
        Map<String, ActionTypeDefinition> newTypes = next.actionTypes().stream().collect(HashMap::new,
                (map, value) -> map.put(value.name(), value), Map::putAll);
        for (String name : newTypes.keySet()) {
            if (!oldTypes.containsKey(name)) changes.add(new SchemaChange("action." + name, "action type added", MigrationClass.SAFE));
            else if (!oldTypes.get(name).equals(newTypes.get(name))) {
                changes.add(new SchemaChange("action." + name, "action parameters changed", MigrationClass.BREAKING));
            }
        }
        for (String name : oldTypes.keySet()) {
            if (!newTypes.containsKey(name)) changes.add(new SchemaChange("action." + name, "action type removed", MigrationClass.BREAKING));
        }
    }

    private void compareProperties(String owner, List<PropertyDefinition> previous,
                                   List<PropertyDefinition> next, List<SchemaChange> changes) {
        Map<String, PropertyDefinition> oldProps = previous.stream().collect(HashMap::new,
                (map, value) -> map.put(value.name(), value), Map::putAll);
        Map<String, PropertyDefinition> newProps = next.stream().collect(HashMap::new,
                (map, value) -> map.put(value.name(), value), Map::putAll);
        for (String name : newProps.keySet()) {
            PropertyDefinition current = newProps.get(name);
            PropertyDefinition old = oldProps.get(name);
            if (old == null) {
                changes.add(new SchemaChange(owner + "." + name, "property added",
                        current.required() ? MigrationClass.BREAKING : MigrationClass.SAFE));
            } else if (!old.equals(current)) {
                MigrationClass classification = old.type().equals(current.type()) && !old.required() && current.required()
                        ? MigrationClass.BREAKING : MigrationClass.COMPATIBLE;
                if (!old.type().equals(current.type()) || old.primary() != current.primary()
                        || !old.unique() && current.unique() || !old.immutable() && current.immutable()) classification = MigrationClass.BREAKING;
                changes.add(new SchemaChange(owner + "." + name, "property definition changed", classification));
            }
        }
        for (String name : oldProps.keySet()) {
            if (!newProps.containsKey(name)) changes.add(new SchemaChange(owner + "." + name, "property removed", MigrationClass.BREAKING));
        }
    }

    private static Map<String, ObjectTypeDefinition> indexObjects(OntologySchema schema) {
        return schema.objectTypes().stream().collect(HashMap::new,
                (map, value) -> map.put(value.name(), value), Map::putAll);
    }
}
