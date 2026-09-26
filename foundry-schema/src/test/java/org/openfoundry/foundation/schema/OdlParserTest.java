package org.openfoundry.foundation.schema;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OdlParserTest {
    private static final String ODL = """
            extend schema @namespace(name: \"example.gov\", version: \"0.1.0\")

            type Person @objectType {
              id: ID! @primary
              name: String! @indexed
              organization: Organization @link(type: \"BelongsTo\", direction: OUTBOUND)
            }

            type Organization @objectType {
              id: ID! @primary
              name: String!
            }

            type BelongsTo @linkType(from: \"Person\", to: \"Organization\", cardinality: MANY_TO_ONE) {
              id: ID! @primary
              startedAt: DateTime!
            }

            type MovePerson @actionType(permission: \"can_move\") {
              person: Person! @param
              organization: Organization! @param
              reason: String @param
            }
            """;

    @Test
    void parsesObjectsLinksAndActions() {
        var schema = new OdlParser().parse(ODL);

        assertEquals("example.gov", schema.namespace());
        assertEquals(2, schema.objectTypes().size());
        assertEquals(1, schema.linkTypes().size());
        assertEquals("Organization", schema.linkTypes().getFirst().toType());
        assertEquals(1, schema.actionTypes().size());
        assertEquals(3, schema.actionTypes().getFirst().parameters().size());
        assertEquals(2, schema.objectTypes().getFirst().properties().size());
    }

    @Test
    void requiresNamespace() {
        assertThrows(SchemaValidationException.class,
                () -> new OdlParser().parse("type Person @objectType { id: ID! @primary }"));
    }
}
