package org.openfoundry.foundation.actions;

import org.openfoundry.foundation.spi.ObjectRecord;

import java.util.Map;

@FunctionalInterface
public interface ExpressionEvaluator {
    boolean evaluate(String expression, Map<String, Object> parameters, ActionActor actor);

    default boolean evaluate(String expression, Map<String, Object> parameters, ActionActor actor, java.time.Instant now) {
        return evaluate(expression, parameters, actor);
    }

    default boolean evaluateBindings(String expression, Map<String, Object> parameters, Map<String, Object> bindings, ActionActor actor, java.time.Instant now) {
        if (!bindings.isEmpty()) throw new UnsupportedOperationException("Evaluator does not support created-object bindings");
        return evaluate(expression, parameters, actor, now);
    }

    static ExpressionEvaluator simple() {
        return (expression, parameters, actor) -> SimpleExpressions.evaluate(expression, parameters, actor);
    }
}
