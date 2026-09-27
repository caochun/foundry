package org.openfoundry.foundation.schema;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DeclarationCompilationTest {
    private static final String PREFIX = "extend schema @namespace(name: \"declarations\", version: \"1.0.0\")\n";

    @Test
    void inheritsInterfacesAndPreservesDefaultsConstraintsAndReadonly() {
        var schema = new OdlParser().parse(PREFIX + """
                interface Identifiable { id: ID! @primary }
                interface Named implements Identifiable { name: String! @constraint(expr: "size(value) > 0") }
                interface Audit { createdAt: DateTime! @readonly }
                type Item implements Named & Audit @objectType @constraint(expr: "this.count >= 0") {
                  count: Int! @default(value: 0)
                }
                """);
        new SchemaCompiler().compile(schema);
        var item = schema.objectTypes().getFirst();
        assertEquals(List.of("Named", "Identifiable", "Audit"), item.interfaces());
        assertEquals(3, schema.interfaces().size());
        assertTrue(item.properties().stream().anyMatch(field -> field.name().equals("createdAt") && field.readOnly()));
        assertTrue(item.properties().stream().anyMatch(field -> field.name().equals("count") && field.hasDefault() && field.defaultValue().equals(0)));
        assertEquals(List.of("this.count >= 0"), item.constraints());
    }

    @Test
    void rejectsUnknownCyclicAndConflictingInterfaceDefinitions() {
        assertThrows(SchemaValidationException.class, () -> new OdlParser().parse(PREFIX + "type Item implements Missing @objectType { id: ID! @primary }"));
        assertThrows(SchemaValidationException.class, () -> new OdlParser().parse(PREFIX + "interface A implements B { a: String } interface B implements A { b: String }"));
        assertThrows(SchemaValidationException.class, () -> new OdlParser().parse(PREFIX + """
                interface Audit { createdAt: DateTime! @readonly }
                type Item implements Audit @objectType { id: ID! @primary createdAt: DateTime! }
                """));
    }

    @Test
    void invalidDefaultsAndConstraintCompilationFailBeforeSchemaApplication() {
        for (String field : List.of("count: Int! @default(value: \"wrong\")", "count: Int! @default(value: null)",
                "count: Int @constraint(expr: \"unknownFunction(value)\")", "count: Int @constraint(expr: \"123\")",
                "token: String! @readonly", "updatedAt: String @readonly")) {
            var schema = new OdlParser().parse(PREFIX + "type Item @objectType { id: ID! @primary " + field + " }");
            assertThrows(SchemaValidationException.class, () -> new SchemaCompiler().compile(schema), field);
        }
    }

    @Test
    void changedDeclarationsChangeDigestAndRequireMigrationReview() {
        String source = PREFIX + "type Item @objectType { id: ID! @primary count: Int @default(value: 1) @constraint(expr: \"value > 0\") }";
        var before = new OdlParser().parse(source);
        var after = new OdlParser().parse(source.replace("value: 1", "value: 2"));
        assertNotEquals(new SchemaCompiler().compile(before).schemaDigest(), new SchemaCompiler().compile(after).schemaDigest());
        assertEquals(MigrationClass.BREAKING, new SchemaDiffer().diff(before, after).classification());
    }
}
