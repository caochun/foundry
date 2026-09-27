package org.openfoundry.foundation.spi;

/** A transaction committed no changes and can be retried after revalidation. */
public final class TransactionConflictException extends IllegalStateException {
    public TransactionConflictException(String message) {
        super(message);
    }
}
