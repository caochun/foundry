package org.openfoundry.foundation.actions;

import org.openfoundry.foundation.spi.ObjectRecord;

import java.util.Map;

@FunctionalInterface
public interface ExpressionEvaluator {
    boolean evaluate(String expression, Map<String, Object> parameters, ActionActor actor);

    default boolean evaluate(String expression, Map<String, Object> parameters, ActionActor actor, java.time.Instant now) {
        return evaluate(expression, parameters, actor);
    }

    static ExpressionEvaluator simple() {
        return (expression, parameters, actor) -> SimpleExpressions.evaluate(expression, parameters, actor);
    }
}
