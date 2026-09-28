package org.openfoundry.foundation.spi;

public final class ObjectSetConflictException extends IllegalStateException {
    public ObjectSetConflictException() { super("ObjectSet changed; reload its definition"); }
}
