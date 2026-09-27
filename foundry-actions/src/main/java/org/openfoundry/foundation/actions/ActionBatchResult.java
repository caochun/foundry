package org.openfoundry.foundation.actions;

import java.util.List;

public record ActionBatchResult(List<ActionResult> results, int succeeded, int failed, int pending) {
    public ActionBatchResult(List<ActionResult> results, int succeeded, int failed) {
        this(results, succeeded, failed, 0);
    }
    public ActionBatchResult { results = List.copyOf(results); }
}
