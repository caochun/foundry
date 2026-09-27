package org.openfoundry.foundation.actions;

import org.openfoundry.foundation.spi.EntityKey;

import java.util.List;

public record ActionResult(boolean success, String actionId, List<EntityKey> affected, String status, List<Failure> errors) {
    public ActionResult(boolean success, String actionId, List<EntityKey> affected) {
        this(success, actionId, affected, success ? "COMPLETED" : "REJECTED", List.of());
    }

    public record Failure(String code, String sideEffect) {}
    public ActionResult {
        affected = List.copyOf(affected);
        errors = List.copyOf(errors);
    }
}
