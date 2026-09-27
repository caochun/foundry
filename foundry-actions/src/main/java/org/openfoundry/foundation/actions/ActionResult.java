package org.openfoundry.foundation.actions;

import org.openfoundry.foundation.spi.EntityKey;

import java.util.List;

public record ActionResult(boolean success, String actionId, List<EntityKey> affected, String status, List<Failure> errors,
                           List<Change> changes) {
    public enum ChangeType { CREATED, UPDATED, DELETED, RESTORED, UNKNOWN }
    public record Change(String typeName, String id, ChangeType changeType) {}
    public record Failure(String code, String sideEffect, String message, String field) {
        public Failure(String code, String sideEffect) { this(code, sideEffect, code, null); }
    }

    public ActionResult(boolean success, String actionId, List<EntityKey> affected) {
        this(success, actionId, affected, success ? "COMPLETED" : "REJECTED", List.of());
    }

    public ActionResult(boolean success, String actionId, List<EntityKey> affected, String status, List<Failure> errors) {
        this(success, actionId, affected, status, errors, affected.stream().map(key -> new Change(key.type(), key.id(), ChangeType.UNKNOWN)).toList());
    }

    public ActionResult {
        affected = List.copyOf(affected);
        errors = List.copyOf(errors);
        changes = List.copyOf(changes);
    }

    public static List<Change> changes(List<EntityKey> affected, List<ActionEffectAccess> access) {
        if (!affected.equals(access.stream().map(ActionEffectAccess::entity).toList())) {
            throw new IllegalStateException("Action result disagrees with recorded effect targets");
        }
        return changes(access);
    }

    public static List<Change> changes(List<ActionEffectAccess> access) {
        return access.stream().map(value -> new Change(value.entity().type(), value.entity().id(), switch (value.kind()) {
            case CREATE_OBJECT, CREATE_LINK -> ChangeType.CREATED;
            case UPDATE_OBJECT -> ChangeType.UPDATED;
            case DELETE_LINK -> ChangeType.DELETED;
        })).toList();
    }
}
