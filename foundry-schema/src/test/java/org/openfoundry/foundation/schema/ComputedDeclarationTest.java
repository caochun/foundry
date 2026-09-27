package org.openfoundry.foundation.schema;

import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.spi.schema.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ComputedDeclarationTest {
    private static final String MODEL = """
            extend schema @namespace(name: "computed", version: "1.0.0")
            interface Occupancy { occupancy: Int @computed(fn: "countLinks", args: {type: "AdmittedTo"}) }
            type Ward implements Occupancy @objectType { id: ID! @primary name: String }
            type Patient @objectType { id: ID! @primary }
            type AdmittedTo @linkType(from: "Patient", to: "Ward", cardinality: MANY_TO_ONE) { id: ID! @primary }
            """;

    @Test
    void retainsOriginalUpstreamWardComputationAndItsReferencedType() throws Exception {
        String source;
        try (var input = getClass().getResourceAsStream("/upstream-v0.3.0/nhs/ward.odl")) {
            source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(new OdlParser().describe(source).references().contains("AdmittedTo"));
        var schema = new OdlParser().parse(source + """
                type Patient @objectType { id: ID! @primary }
                type Bed @objectType { id: ID! @primary }
                type AdmittedTo @linkType(from: "Patient", to: "Ward", cardinality: MANY_TO_ONE) { id: ID! @primary }
                type BedInWard @linkType(from: "Bed", to: "Ward", cardinality: MANY_TO_ONE) { id: ID! @primary }
                """);
        new SchemaCompiler().compile(schema);
        var ward = schema.objectTypes().getFirst();
        var field = ward.computedFields().getFirst();
        assertEquals("currentOccupancy", field.name());
        assertEquals("countLinks", field.function());
        assertEquals(Map.of("type", "AdmittedTo"), field.arguments());
        assertEquals(ComputedFieldDefinition.Cache.LAZY, field.cache());
        assertFalse(ward.properties().stream().anyMatch(property -> property.name().equals("currentOccupancy")));
    }

    @Test
    void inheritedComputedFieldsCannotBeDroppedOrChangedToAnotherFieldKind() {
        var schema = new OdlParser().parse(MODEL);
        new SchemaCompiler().compile(schema);
        var ward = schema.objectTypes().getFirst();
        assertEquals(1, ward.computedFields().size());
        var unresolved = new ObjectTypeDefinition(ward.name(), ward.properties(), ward.interfaces(), ward.constraints(), ward.linkFields());
        var malformed = new OntologySchema(schema.namespace(), schema.version(), List.of(unresolved, schema.objectTypes().getLast()),
                schema.linkTypes(), schema.actionTypes(), schema.enums(), schema.interfaces());
        assertThrows(IllegalArgumentException.class, () -> PropertyValues.requireSchema(malformed));
        assertThrows(SchemaValidationException.class, () -> new OdlParser().parse(MODEL.replace("name: String", "occupancy: Int")));
    }

    @Test
    void unsupportedFunctionsStrategiesBadDirectionsAndWrongResultsNeverDisappearSilently() {
        for (String replacement : List.of(
                "occupancy: Int @computed(fn: \"unknown\", args: {type: \"AdmittedTo\"})",
                "occupancy: Int @computed(fn: \"countLinks\", args: {type: \"AdmittedTo\"}, cache: EAGER)",
                "occupancy: Int @computed(fn: \"countLinks\", args: {type: \"AdmittedTo\"}, cache: TTL, ttl: \"PT1M\")",
                "occupancy: String @computed(fn: \"countLinks\", args: {type: \"AdmittedTo\"})",
                "occupancy: Int @computed(fn: \"countLinks\", args: {type: \"Missing\"})",
                "occupancy: Int @computed(fn: \"countLinks\", args: {type: \"AdmittedTo\", direction: OUTBOUND})",
                "occupancy: Int @computed(fn: \"countLinks\", args: {type: \"AdmittedTo\", direction: BAD})",
                "occupancy: Int @computed(fn: \"countLinks\", args: {type: \"AdmittedTo\", extra: 1})")) {
            String before = "occupancy: Int @computed(fn: \"countLinks\", args: {type: \"AdmittedTo\"})";
            var schema = new OdlParser().parse(MODEL.replace(before, replacement));
            assertEquals(1, schema.objectTypes().getFirst().computedFields().size());
            assertThrows(SchemaValidationException.class, () -> new SchemaCompiler().compile(schema), replacement);
        }
        assertThrows(SchemaValidationException.class, () -> new OdlParser().parse(MODEL.replace("occupancy: Int @computed", "occupancy: Int @immutable @computed")));
    }

    @Test
    void digestAndDiffTrackComputationSemanticsWithoutDependingOnArgumentOrder() {
        var parser = new OdlParser();
        var before = parser.parse(MODEL.replace("{type: \"AdmittedTo\"}", "{type: \"AdmittedTo\", direction: INBOUND}"));
        var reordered = parser.parse(MODEL.replace("{type: \"AdmittedTo\"}", "{direction: INBOUND, type: \"AdmittedTo\"}"));
        assertEquals(new SchemaCompiler().compile(before).schemaDigest(), new SchemaCompiler().compile(reordered).schemaDigest());
        var changed = parser.parse(MODEL.replace("occupancy: Int @computed", "occupancy: Int @sensitive @computed"));
        assertNotEquals(new SchemaCompiler().compile(before).schemaDigest(), new SchemaCompiler().compile(changed).schemaDigest());
        assertEquals(MigrationClass.BREAKING, new SchemaDiffer().diff(before, changed).classification());
    }
}
