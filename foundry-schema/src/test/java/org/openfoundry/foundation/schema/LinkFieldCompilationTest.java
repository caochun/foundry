package org.openfoundry.foundation.schema;

import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.spi.StorageProvider.Direction;
import org.openfoundry.foundation.spi.schema.ObjectTypeDefinition;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.spi.schema.PropertyValues;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LinkFieldCompilationTest {
    private static final String MODEL = """
            extend schema @namespace(name: "navigation", version: "1.0.0")
            interface Borrowable {
              borrower: Member @link(type: "BorrowedBy")
            }
            type Book implements Borrowable @objectType {
              id: ID! @primary
              loans: [BorrowedBy!]! @link(type: "BorrowedBy", history: true)
            }
            type Member @objectType {
              id: ID! @primary
              books: [Book!]! @link(type: "BorrowedBy", direction: INBOUND)
            }
            type BorrowedBy @linkType(from: "Book", to: "Member", cardinality: MANY_TO_ONE) {
              id: ID! @primary
            }
            """;

    @Test
    void unmodifiedUpstreamLibrarySchemaRetainsBothDirections() throws IOException {
        var source = new StringBuilder();
        for (String name : List.of("enums", "book", "member", "links", "actions")) {
            try (var stream = getClass().getResourceAsStream("/upstream-v0.3.0/library/" + name + ".odl")) {
                source.append(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).append('\n');
            }
        }
        var schema = new SchemaCompiler().compile(new OdlParser().parse(source.toString())).schema();
        var book = schema.objectTypes().stream().filter(type -> type.name().equals("Book")).findFirst().orElseThrow();
        var member = schema.objectTypes().stream().filter(type -> type.name().equals("Member")).findFirst().orElseThrow();
        assertEquals("borrower", book.linkFields().getFirst().name());
        assertEquals(Direction.OUTBOUND, book.linkFields().getFirst().direction());
        assertEquals("Member", book.linkFields().getFirst().targetType());
        assertEquals("books", member.linkFields().getFirst().name());
        assertEquals(Direction.INBOUND, member.linkFields().getFirst().direction());
        assertTrue(member.linkFields().getFirst().many());
        assertTrue(member.linkFields().getFirst().required());
        assertFalse(book.properties().stream().anyMatch(field -> field.name().equals("borrower")));
    }

    @Test
    void inheritedNavigationIsRetainedAndCannotBeRemovedThroughJavaSchema() {
        var schema = new OdlParser().parse(MODEL);
        new SchemaCompiler().compile(schema);
        var book = schema.objectTypes().getFirst();
        assertEquals(2, book.linkFields().size());
        var unresolved = new ObjectTypeDefinition(book.name(), book.properties(), book.interfaces(), book.constraints());
        var bad = new OntologySchema(schema.namespace(), schema.version(), List.of(unresolved, schema.objectTypes().getLast()),
                schema.linkTypes(), schema.actionTypes(), schema.enums(), schema.interfaces());
        assertThrows(IllegalArgumentException.class, () -> PropertyValues.requireSchema(bad));
        assertThrows(SchemaValidationException.class, () -> new SchemaCompiler().compile(bad));
    }

    @Test
    void invalidEndpointHistoryTargetAndCardinalityAreRejected() {
        for (String source : List.of(
                MODEL.replace("@link(type: \"BorrowedBy\")", "@link(type: \"Missing\")"),
                MODEL.replace("direction: INBOUND", "direction: OUTBOUND"),
                MODEL.replace("[Book!]! @link", "Book @link"),
                MODEL.replace("[BorrowedBy!]! @link", "BorrowedBy @link"),
                MODEL.replace("borrower: Member", "borrower: Book"),
                MODEL.replace("borrower: Member", "borrower: [[Member]]"),
                MODEL.replace("history: true", "history: \"true\""),
                MODEL.replace("history: true", "histroy: true"),
                MODEL.replace("borrower: Member", "borrower: String @default(value: \"bad\")"))) {
            var error = assertThrows(RuntimeException.class, () -> new SchemaCompiler().compile(new OdlParser().parse(source)));
            assertTrue(error instanceof IllegalArgumentException || error instanceof SchemaValidationException);
        }
    }

    @Test
    void inheritedFieldKindsAndProtectedDeclarationsCannotConflict() {
        for (String field : List.of("borrower: String", "borrower: Int @computed(fn: \"countLinks\")", "borrower: Member @link(type: \"BorrowedBy\") @sensitive")) {
            String source = MODEL.replace("id: ID! @primary\n  loans", "id: ID! @primary\n  " + field + "\n  loans");
            var error = assertThrows(RuntimeException.class, () -> new SchemaCompiler().compile(new OdlParser().parse(source)));
            assertTrue(error instanceof IllegalArgumentException || error instanceof SchemaValidationException);
        }
        assertThrows(SchemaValidationException.class, () -> new OdlParser().parse(MODEL.replace(
                "borrower: Member @link(type: \"BorrowedBy\")", "borrower: Member @link(type: \"BorrowedBy\") @immutable")));
    }

    @Test
    void navigationChangesAffectDigestAndMigrationClassification() {
        var before = new OdlParser().parse(MODEL);
        var after = new OdlParser().parse(MODEL.replace("history: true", "history: false"));
        assertNotEquals(new SchemaCompiler().compile(before).schemaDigest(), new SchemaCompiler().compile(after).schemaDigest());
        assertEquals(MigrationClass.BREAKING, new SchemaDiffer().diff(before, after).classification());
    }
}
