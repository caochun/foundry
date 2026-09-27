package org.openfoundry.foundation.validation;

import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.spi.schema.PropertyValidationException;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ConstraintProgramsTest {
    @Test
    void complexStandardExpressionsExecuteAndNonBooleanResultsNeverPass() {
        var rules = new ConstraintPrograms();
        rules.require("value.all(x, x > 0) && size(this.name) > 0", true, "values", Map.of("name", "Test"), List.of(1, 2));
        assertThrows(IllegalArgumentException.class, () -> rules.validate("42", true));
        assertThrows(IllegalArgumentException.class, () -> rules.validate("missingFunction(value)", true));
        var error = assertThrows(PropertyValidationException.class, () -> rules.require("this.missing", false, "$type", Map.of("secret", "private-value"), null));
        assertEquals("CONSTRAINT_EVALUATION_ERROR", error.code());
        assertFalse(error.getMessage().contains("private-value"));
    }

    @Test
    void regexUsesRe2ForLiteralAndDynamicPatterns() {
        var rules = new ConstraintPrograms();
        rules.validate("value.matches('^a+$')", true);
        rules.require("value.matches('^a+$')", true, "text", Map.of(), "aaa");
        rules.require("value.matches(this.pattern)", true, "text", Map.of("pattern", "a+"), "aaa");
        // Backreferences are accepted by java.util.regex but rejected by RE2.
        var error = assertThrows(PropertyValidationException.class, () -> rules.require(
                "value.matches(this.pattern)", true, "text", Map.of("pattern", "(a)\\1"), "aa"));
        assertEquals("CONSTRAINT_EVALUATION_ERROR", error.code());
        assertTimeout(java.time.Duration.ofSeconds(3), () -> {
            var rejected = assertThrows(PropertyValidationException.class, () -> rules.require(
                    "value.matches('^(a+)+$')", true, "text", Map.of(), "a".repeat(100000) + "!"));
            assertEquals("CONSTRAINT_VIOLATION", rejected.code());
        });
    }

    @Test
    void evaluationBudgetStopsNestedComprehensionsAndIsResetForTheNextCall() {
        var rules = new ConstraintPrograms();
        var values = java.util.stream.IntStream.range(0, 500).boxed().toList();
        assertThrows(PropertyValidationException.class, () -> rules.require("value.all(x, value.all(y, x + y >= 0))", true, "values", Map.of(), values));
        assertDoesNotThrow(() -> rules.require("value > 0", true, "count", Map.of(), 1));
    }
}
