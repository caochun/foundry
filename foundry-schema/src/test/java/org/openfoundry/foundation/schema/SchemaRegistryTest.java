package org.openfoundry.foundation.schema;

import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.spi.schema.ObjectTypeDefinition;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.spi.schema.PropertyDefinition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SchemaRegistryTest {
    private static final PropertyDefinition ID = new PropertyDefinition("id", "ID", true, true, true, true, false, true);

    @Test
    void versionsAreImmutableAndAdditiveChangesAreSafe() {
        InMemorySchemaRegistry registry = new InMemorySchemaRegistry();
        registry.apply(schema(List.of(ID)), null);
        SchemaVersion second = registry.apply(schema(List.of(ID, new PropertyDefinition(
                "nickname", "String", false, false, false, false, false, false))), null);

        assertEquals(2, second.version());
        assertEquals(MigrationClass.SAFE, second.classification());
        assertEquals(2, registry.history().size());
    }

    @Test
    void breakingChangesRequireApproval() {
        InMemorySchemaRegistry registry = new InMemorySchemaRegistry();
        registry.apply(schema(List.of(ID)), null);
        OntologySchema breaking = schema(List.of(ID, new PropertyDefinition(
                "requiredName", "String", true, false, false, false, false, false)));

        assertThrows(SchemaValidationException.class, () -> registry.apply(breaking, null));
        assertEquals(1, registry.history().size());
        registry.apply(breaking, new MigrationPlan("backfill requiredName", true));
        assertEquals(2, registry.history().size());
    }

    @Test
    void propertyTypeAndUniquenessChangesAreBreaking() {
        var before = schema(List.of(ID, new PropertyDefinition("value", "String", false, false, false, false, false, false)));
        var numeric = schema(List.of(ID, new PropertyDefinition("value", "Int", false, false, false, false, false, false)));
        var unique = schema(List.of(ID, new PropertyDefinition("value", "String", false, false, true, false, false, false)));
        assertEquals(MigrationClass.BREAKING, new SchemaDiffer().diff(before, numeric).classification());
        assertEquals(MigrationClass.BREAKING, new SchemaDiffer().diff(before, unique).classification());
    }

    @Test
    void enumMembersAreRetainedAndNarrowingRequiresMigration() {
        String source = """
                extend schema @namespace(name: "enums", version: "0.1.0")
                enum Status { ACTIVE INACTIVE }
                type Item @objectType { id: ID! @primary state: Status! labels: [String!] }
                """;
        var before = new OdlParser().parse(source);
        assertEquals(List.of("ACTIVE", "INACTIVE"), before.enums().get("Status"));
        assertEquals("[String!]", before.objectTypes().getFirst().properties().get(2).type());
        var narrowed = new OdlParser().parse(source.replace("ACTIVE INACTIVE", "ACTIVE"));
        assertEquals(MigrationClass.BREAKING, new SchemaDiffer().diff(before, narrowed).classification());
        org.junit.jupiter.api.Assertions.assertNotEquals(new SchemaCompiler().compile(before).schemaDigest(), new SchemaCompiler().compile(narrowed).schemaDigest());
    }

    @Test
    void equivalentNumericDefaultRepresentationsDoNotCreateFalseMigrationChanges() {
        var decimal = new PropertyDefinition("data", "JSON", false, false, false, false, false, false, false, true,
                java.util.Map.of("fraction", new java.math.BigDecimal("0.1")), List.of());
        var floating = new PropertyDefinition("data", "JSON", false, false, false, false, false, false, false, true,
                java.util.Map.of("fraction", 0.1d), List.of());
        var before = schema(List.of(ID, decimal));
        var same = schema(List.of(ID, floating));
        assertEquals(SchemaFingerprint.of(before), SchemaFingerprint.of(same));
        org.junit.jupiter.api.Assertions.assertTrue(new SchemaDiffer().diff(before, same).isEmpty());
        var added = schema(List.of(ID, floating, new PropertyDefinition("optional", "String", false, false, false, false, false, false)));
        assertEquals(MigrationClass.SAFE, new SchemaDiffer().diff(before, added).classification());
    }

    private static OntologySchema schema(List<PropertyDefinition> properties) {
        return new OntologySchema("example", "0.1.0", List.of(new ObjectTypeDefinition("Person", properties)), List.of(), List.of());
    }
}
