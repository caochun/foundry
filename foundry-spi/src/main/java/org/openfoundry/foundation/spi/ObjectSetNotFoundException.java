package org.openfoundry.foundation.spi;

public final class ObjectSetNotFoundException extends IllegalArgumentException {
    public ObjectSetNotFoundException() { super("ObjectSet is not available"); }
}
