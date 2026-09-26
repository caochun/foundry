package org.openfoundry.foundation.pack;

public final class PackLoadException extends RuntimeException {
    public PackLoadException(String message) {
        super(message);
    }

    public PackLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
