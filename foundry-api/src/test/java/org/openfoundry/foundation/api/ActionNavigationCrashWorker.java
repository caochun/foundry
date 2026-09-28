package org.openfoundry.foundation.api;

import org.h2.jdbcx.JdbcDataSource;

import static org.openfoundry.foundation.api.ActionNavigationTest.*;

/** Exit after the business/read evidence commit but before acknowledging the external delivery. */
public final class ActionNavigationCrashWorker {
    public static void main(String[] args) {
        var data = new JdbcDataSource();
        data.setURL(args[0]);
        var fixture = emptyFixture(data);
        var action = withEvent(action(UPDATE, null), "RETRY_INDEFINITELY");
        fixture.app(action, invocation -> Runtime.getRuntime().halt(83)).execute(action, CTX, PRINCIPAL, INPUT, "crash");
        throw new IllegalStateException("Crash boundary was not reached");
    }
}
