package org.openfoundry.foundation.actions;

import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;
import org.openfoundry.foundation.spi.schema.Cardinality;
import org.openfoundry.foundation.spi.schema.LinkTypeDefinition;
import org.openfoundry.foundation.spi.schema.ObjectTypeDefinition;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.spi.schema.PropertyDefinition;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionRuntimeTest {
    @Test
    void parsesAndExecutesGovernedObjectUpdate() {
        String yaml = """
                action: PromotePerson
                version: 1
                reversible: false
                preconditions:
                  - expr: "actor.hasRole('admin')"
                    error: "admin required"
                effects:
                  - type: updateObject
                    target: person
                    set:
                      status: PROMOTED
                """;
        ActionManifest manifest = new ActionManifestParser().parse(yaml);
        InMemoryStorageProvider storage = new InMemoryStorageProvider();
        RequestContext context = RequestContext.system("tenant", "operator");
        PropertyDefinition id = new PropertyDefinition("id", "ID", true, true, true, true, false, true);
        storage.applySchema(context, new OntologySchema("example", "0.1.0",
                List.of(new ObjectTypeDefinition("Person", List.of(id))), List.of(), List.of()));
        ObjectRecord person;
        try (var transaction = storage.beginTransaction(context)) {
            person = transaction.createObject("Person", "p-1", Map.of("status", "ACTIVE"));
            transaction.commit();
        }

        ActionResult result = new ActionExecutor().execute(manifest, context,
                new ActionActor("operator", Set.of("admin")), Map.of("person", person), storage);

        assertTrue(result.success());
        assertEquals("PROMOTED", storage.getObject(context, "Person", "p-1").properties().get("status"));
        assertEquals(1, storage.auditEntries(context).size());
        assertEquals("openfoundry.action.completed", storage.outboxEntries(context).getFirst().type());
    }
}
