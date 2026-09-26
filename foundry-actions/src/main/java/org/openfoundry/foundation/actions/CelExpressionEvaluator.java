package org.openfoundry.foundation.actions;

import org.openfoundry.foundation.spi.ObjectRecord;
import org.projectnessie.cel.CEL;
import org.projectnessie.cel.Env;
import org.projectnessie.cel.EnvOption;
import org.projectnessie.cel.Program;
import org.projectnessie.cel.checker.Decls;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** CEL-backed evaluator for Action preconditions. */
public final class CelExpressionEvaluator implements ExpressionEvaluator {
    private final Env environment;
    private final Map<String, Program> programs = new ConcurrentHashMap<>();

    public CelExpressionEvaluator() {
        this.environment = Env.newEnv(EnvOption.declarations(
                Decls.newVar("actor", Decls.Dyn),
                Decls.newVar("params", Decls.Dyn),
                Decls.newVar("now", Decls.Timestamp)));
    }

    @Override
    public boolean evaluate(String expression, Map<String, Object> parameters, ActionActor actor) {
        String normalized = normalize(expression);
        String cacheKey = normalized + "|" + parameters.keySet().stream().sorted().toList();
        Program program = programs.computeIfAbsent(cacheKey, ignored -> compile(normalized, parameters.keySet()));
        Map<String, Object> activation = new HashMap<>();
        Map<String, Object> actorValue = new HashMap<>();
        actorValue.put("id", actor.id());
        actorValue.put("roles", actor.roles().stream().toList());
        activation.put("actor", actorValue);
        activation.put("params", parameters);
        activation.putAll(parameters);
        activation.put("now", java.time.Instant.now());
        return program.eval(activation).getVal().booleanValue();
    }

    private Program compile(String expression, java.util.Set<String> parameterNames) {
        java.util.List<com.google.api.expr.v1alpha1.Decl> declarations = parameterNames.stream()
                .map(name -> Decls.newVar(name, Decls.Dyn)).toList();
        Env env = environment.extend(EnvOption.declarations(declarations));
        Env.AstIssuesTuple compiled = env.compile(expression);
        if (compiled.hasIssues()) throw new IllegalArgumentException("invalid CEL expression: " + compiled.getIssues());
        return CEL.newProgram(env, compiled.getAst());
    }

    private static String normalize(String expression) {
        return expression.replaceAll("actor\\.hasRole\\(([^)]+)\\)", "actor.roles.exists(r, r == $1)");
    }
}
