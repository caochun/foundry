package org.openfoundry.foundation.validation;

import org.openfoundry.foundation.spi.schema.PropertyValidationException;
import com.google.re2j.Pattern;
import org.projectnessie.cel.common.types.BoolT;
import org.projectnessie.cel.common.types.Err;
import org.projectnessie.cel.common.types.Overloads;
import org.projectnessie.cel.common.types.ref.Val;
import org.projectnessie.cel.interpreter.functions.Overload;
import org.projectnessie.cel.Env;
import org.projectnessie.cel.EnvOption;
import org.projectnessie.cel.Program;
import org.projectnessie.cel.ProgramOption;
import org.projectnessie.cel.checker.Decls;
import org.projectnessie.cel.interpreter.InterpretableDecorator;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pure CEL constraints with bounded compilation cache and per-evaluation work budget. */
public final class ConstraintPrograms {
    private final ThreadLocal<int[]> budget = new ThreadLocal<>();
    private final Map<String, Program> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Program> entry) {
            return size() > 1024;
        }
    };

    public void validate(String expression, boolean field) {
        program(expression, field);
    }

    public void require(String expression, boolean field, String name, Map<String, Object> properties, Object value) {
        var variables = new HashMap<String, Object>();
        variables.put("this", properties);
        if (field) {
            variables.put("value", value);
        }
        budget.set(new int[]{100000});
        try {
            Object result = program(expression, field).eval(variables).getVal().value();
            if (!(result instanceof Boolean accepted)) {
                throw new PropertyValidationException("CONSTRAINT_EVALUATION_ERROR", name);
            }
            if (!accepted) {
                throw new PropertyValidationException("CONSTRAINT_VIOLATION", name);
            }
        } catch (PropertyValidationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new PropertyValidationException("CONSTRAINT_EVALUATION_ERROR", name);
        } finally {
            budget.remove();
        }
    }

    private static Val matches(Val input, Val expression) {
        if (!(input.value() instanceof String text) || !(expression.value() instanceof String pattern)) {
            return Err.newErr("matches requires strings");
        }
        if (pattern.length() > 8192 || text.length() > 1_000_000) {
            return Err.newErr("matches input limit exceeded");
        }
        try {
            return Pattern.compile(pattern).matcher(text).find() ? BoolT.True : BoolT.False;
        } catch (IllegalArgumentException failure) {
            return Err.newErr("Invalid RE2 pattern");
        }
    }

    private synchronized Program program(String expression, boolean field) {
        if (expression == null || expression.isBlank() || expression.length() > 8192) {
            throw new IllegalArgumentException("Constraint must have 1–8192 characters");
        }
        String key = (field ? "field:" : "type:") + expression;
        var existing = cache.get(key);
        if (existing != null) {
            return existing;
        }
        var declarations = new java.util.ArrayList<com.google.api.expr.v1alpha1.Decl>();
        declarations.add(Decls.newVar("this", Decls.Dyn));
        if (field) {
            declarations.add(Decls.newVar("value", Decls.Dyn));
        }
        var environment = Env.newEnv(EnvOption.declarations(declarations));
        var compiled = environment.compile(expression);
        if (compiled.hasIssues()) {
            throw new IllegalArgumentException("Invalid CEL constraint: " + compiled.getIssues());
        }
        var resultType = compiled.getAst().getResultType();
        if (!resultType.equals(Decls.Bool) && !resultType.equals(Decls.Dyn)) {
            throw new IllegalArgumentException("Constraint must return a boolean");
        }
        var program = environment.program(compiled.getAst(),
                ProgramOption.functions(Overload.binary(Overloads.MatchesString, ConstraintPrograms::matches)),
                ProgramOption.customDecorator(InterpretableDecorator.decObserveEval((id, value) -> {
                    var remaining = budget.get();
                    if (remaining != null && --remaining[0] < 0) {
                        throw new IllegalArgumentException("Constraint evaluation budget exceeded");
                    }
                })));
        cache.put(key, program);
        return program;
    }
}
