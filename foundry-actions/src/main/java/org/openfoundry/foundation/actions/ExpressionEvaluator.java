package org.openfoundry.foundation.actions;

import org.openfoundry.foundation.spi.ObjectRecord;

import java.util.Map;

@FunctionalInterface
public interface ExpressionEvaluator {
    boolean evaluate(String expression, Map<String, Object> parameters, ActionActor actor);

    static ExpressionEvaluator simple() {
        return (expression, parameters, actor) -> SimpleExpressions.evaluate(expression, parameters, actor);
    }
}
