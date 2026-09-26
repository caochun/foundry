package org.openfoundry.foundation.actions;

import org.openfoundry.foundation.spi.EntityKey;

import java.util.List;

public record ActionResult(boolean success, String actionId, List<EntityKey> affected) {
    public ActionResult {
        affected = List.copyOf(affected);
    }
}
