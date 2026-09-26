package org.openfoundry.foundation.actions;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CelExpressionEvaluatorTest {
    @Test
    void evaluatesObjectFieldsAndRolePredicates() {
        CelExpressionEvaluator evaluator = new CelExpressionEvaluator();
        ActionActor actor = new ActionActor("u-1", Set.of("admin"));
        Map<String, Object> person = Map.of("status", "ACTIVE");
        assertTrue(evaluator.evaluate("person.status == 'ACTIVE' && actor.hasRole('admin')",
                Map.of("person", person), actor));
        assertFalse(evaluator.evaluate("person.status == 'DISABLED'", Map.of("person", person), actor));
    }
}
