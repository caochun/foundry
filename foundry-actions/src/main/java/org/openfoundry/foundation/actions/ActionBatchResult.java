package org.openfoundry.foundation.actions;

import java.util.List;

public record ActionBatchResult(List<ActionResult> results, int succeeded, int failed) {
    public ActionBatchResult { results = List.copyOf(results); }
}
