package org.openfoundry.foundation.actions;

/** Stable error codes for a filtered deletion that expected exactly one active relationship. */
public final class LinkResolutionException extends IllegalArgumentException {
    private final String code;

    public LinkResolutionException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
