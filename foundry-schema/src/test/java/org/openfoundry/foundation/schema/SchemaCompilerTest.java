package org.openfoundry.foundation.schema;

import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.spi.schema.Cardinality;
import org.openfoundry.foundation.spi.schema.LinkTypeDefinition;
import org.openfoundry.foundation.spi.schema.ObjectTypeDefinition;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.spi.schema.PropertyDefinition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SchemaCompilerTest {
    private static final PropertyDefinition ID =
            new PropertyDefinition("id", "ID", true, true, true, true, false, true);

    @Test
    void compilesDeterministicSchemaDigest() {
        OntologySchema schema = schema();
        SchemaCompiler compiler = new SchemaCompiler();

        assertEquals(compiler.compile(schema).schemaDigest(), compiler.compile(schema).schemaDigest());
    }

    @Test
    void rejectsUnknownLinkEndpoint() {
        OntologySchema schema = new OntologySchema(
                "example", "0.1.0",
                List.of(new ObjectTypeDefinition("Person", List.of(ID))),
                List.of(new LinkTypeDefinition("BelongsTo", "Person", "Organization",
                        Cardinality.MANY_TO_ONE, List.of(ID))),
                List.of());

        assertThrows(SchemaValidationException.class, () -> new SchemaCompiler().compile(schema));
    }

    @Test
    void rejectsLinkWithoutPrimaryProperty() {
        OntologySchema schema = new OntologySchema(
                "example", "0.1.0",
                List.of(
                        new ObjectTypeDefinition("Person", List.of(ID)),
                        new ObjectTypeDefinition("Organization", List.of(ID))),
                List.of(new LinkTypeDefinition("BelongsTo", "Person", "Organization",
                        Cardinality.MANY_TO_ONE, List.of(
                                new PropertyDefinition("startedAt", "DateTime", true,
                                        false, false, false, false, false)))),
                List.of());

        assertThrows(SchemaValidationException.class, () -> new SchemaCompiler().compile(schema));
    }

    private static OntologySchema schema() {
        return new OntologySchema(
                "example", "0.1.0",
                List.of(
                        new ObjectTypeDefinition("Person", List.of(ID)),
                        new ObjectTypeDefinition("Organization", List.of(ID))),
                List.of(new LinkTypeDefinition("BelongsTo", "Person", "Organization",
                        Cardinality.MANY_TO_ONE, List.of(ID))),
                List.of());
    }
}
