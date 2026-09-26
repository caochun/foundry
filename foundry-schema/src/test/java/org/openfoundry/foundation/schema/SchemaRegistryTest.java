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

    private static OntologySchema schema(List<PropertyDefinition> properties) {
        return new OntologySchema("example", "0.1.0", List.of(new ObjectTypeDefinition("Person", properties)), List.of(), List.of());
    }
}
