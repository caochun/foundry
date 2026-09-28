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
        if (!previous.namespace().equals(next.namespace())) changes.add(new SchemaChange("namespace", "schema namespace changed", MigrationClass.BREAKING));
        if (!previous.version().equals(next.version())) changes.add(new SchemaChange("version", "declared schema version changed", MigrationClass.SAFE));
        for (var entry : next.enums().entrySet()) {
            var old = previous.enums().get(entry.getKey());
            if (old == null) changes.add(new SchemaChange("enum." + entry.getKey(), "enum added", MigrationClass.SAFE));
            else if (!new HashSet<>(old).equals(new HashSet<>(entry.getValue()))) changes.add(new SchemaChange("enum." + entry.getKey(), "enum members changed",
                    entry.getValue().containsAll(old) ? MigrationClass.COMPATIBLE : MigrationClass.BREAKING));
        }
        for (String name : previous.enums().keySet()) if (!next.enums().containsKey(name)) changes.add(new SchemaChange("enum." + name, "enum removed", MigrationClass.BREAKING));
        if (!SchemaFingerprint.equivalent(previous.interfaces().stream().sorted(java.util.Comparator.comparing(org.openfoundry.foundation.spi.schema.InterfaceDefinition::name)).toList(),
                next.interfaces().stream().sorted(java.util.Comparator.comparing(org.openfoundry.foundation.spi.schema.InterfaceDefinition::name)).toList())) {
            changes.add(new SchemaChange("interfaces", "interface definitions or ancestry changed", MigrationClass.BREAKING));
        }
        compareObjects(previous, next, changes);
        compareLinks(previous, next, changes);
        compareActions(previous, next, changes);
        changes.sort(java.util.Comparator.comparing(SchemaChange::path).thenComparing(SchemaChange::detail));
        return new SchemaDiff(changes);
    }

    private void compareObjects(OntologySchema previous, OntologySchema next, List<SchemaChange> changes) {
        Map<String, ObjectTypeDefinition> oldTypes = indexObjects(previous);
        Map<String, ObjectTypeDefinition> newTypes = indexObjects(next);
        for (String name : newTypes.keySet()) {
            if (!oldTypes.containsKey(name)) changes.add(new SchemaChange("object." + name, "object type added", MigrationClass.SAFE));
            else {
                var old = oldTypes.get(name);
                var current = newTypes.get(name);
                compareProperties("object." + name, old.properties(), current.properties(), changes);
                compareLinkFields("object." + name, old.linkFields(), current.linkFields(), changes);
                compareComputedFields("object." + name, old.computedFields(), current.computedFields(), changes);
                if (!old.constraints().equals(current.constraints()) || !old.interfaces().equals(current.interfaces())) {
                    changes.add(new SchemaChange("object." + name, "type constraints or interfaces changed", MigrationClass.BREAKING));
                }
            }
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
            compareLinkFields("link." + name, old.linkFields(), current.linkFields(), changes);
            compareComputedFields("link." + name, old.computedFields(), current.computedFields(), changes);
            if (!old.constraints().equals(current.constraints()) || !old.interfaces().equals(current.interfaces())) {
                changes.add(new SchemaChange("link." + name, "type constraints or interfaces changed", MigrationClass.BREAKING));
            }
        }
        for (String name : oldTypes.keySet()) {
            if (!newTypes.containsKey(name)) changes.add(new SchemaChange("link." + name, "link type removed", MigrationClass.BREAKING));
        }
    }

    private void compareLinkFields(String owner, List<org.openfoundry.foundation.spi.schema.LinkFieldDefinition> previous,
                                   List<org.openfoundry.foundation.spi.schema.LinkFieldDefinition> next, List<SchemaChange> changes) {
        var old = new HashMap<String, org.openfoundry.foundation.spi.schema.LinkFieldDefinition>();
        previous.forEach(field -> old.put(field.name(), field));
        for (var field : next) {
            var before = old.remove(field.name());
            if (before == null) {
                changes.add(new SchemaChange(owner + "." + field.name(), "relationship projection added", MigrationClass.SAFE));
            } else if (!before.equals(field)) {
                changes.add(new SchemaChange(owner + "." + field.name(), "relationship projection changed", MigrationClass.BREAKING));
            }
        }
        old.keySet().forEach(name -> changes.add(new SchemaChange(owner + "." + name, "relationship projection removed", MigrationClass.BREAKING)));
    }

    private void compareComputedFields(String owner, List<org.openfoundry.foundation.spi.schema.ComputedFieldDefinition> previous,
                                       List<org.openfoundry.foundation.spi.schema.ComputedFieldDefinition> next, List<SchemaChange> changes) {
        var old = new HashMap<String, org.openfoundry.foundation.spi.schema.ComputedFieldDefinition>();
        previous.forEach(field -> old.put(field.name(), field));
        for (var field : next) {
            var before = old.remove(field.name());
            if (before == null) changes.add(new SchemaChange(owner + "." + field.name(), "computed field added", MigrationClass.SAFE));
            else if (!before.equals(field)) changes.add(new SchemaChange(owner + "." + field.name(), "computed field changed", MigrationClass.BREAKING));
        }
        old.keySet().forEach(name -> changes.add(new SchemaChange(owner + "." + name, "computed field removed", MigrationClass.BREAKING)));
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
            } else if (!SchemaFingerprint.equivalent(old, current)) {
                MigrationClass classification = old.type().equals(current.type()) && !old.required() && current.required()
                        ? MigrationClass.BREAKING : MigrationClass.COMPATIBLE;
                if (!old.type().equals(current.type()) || old.primary() != current.primary()
                        || !old.unique() && current.unique() || !old.immutable() && current.immutable()
                        || old.readOnly() != current.readOnly() || !old.constraints().equals(current.constraints())
                        || old.hasDefault() != current.hasDefault() || !SchemaFingerprint.equivalent(old.defaultValue(), current.defaultValue())) classification = MigrationClass.BREAKING;
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
