package org.openfoundry.foundation.sync;

import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.Transaction;

/** Trusted pipeline/target policy. Target and transaction are null for the check before connector I/O. */
@FunctionalInterface
public interface SyncAuthorizer {
    boolean allowed(RequestContext context, String connector, MappingConfig mapping, EntityKey target, Transaction transaction);

    static SyncAuthorizer denyAll() { return (context, connector, mapping, target, transaction) -> false; }
}
