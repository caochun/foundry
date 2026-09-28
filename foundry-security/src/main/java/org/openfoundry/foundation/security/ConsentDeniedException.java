package org.openfoundry.foundation.security;

import org.openfoundry.foundation.spi.EntityKey;

public final class ConsentDeniedException extends SecurityException {
    private final EntityKey subject;
    private final String purpose;
    public ConsentDeniedException(EntityKey subject, String purpose) {
        super("Consent is not granted for the requested operation");
        this.subject = subject; this.purpose = purpose;
    }
    public EntityKey subject() { return subject; }
    public String purpose() { return purpose; }
}
