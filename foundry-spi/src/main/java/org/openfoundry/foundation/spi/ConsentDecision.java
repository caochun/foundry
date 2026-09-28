package org.openfoundry.foundation.spi;

/** Purpose names are deployment-defined, not a closed medical enum. */
public record ConsentDecision(boolean allowed, String purpose, Basis basis, long revision) {
    public enum Basis { EXPLICIT_CONSENT, LEGITIMATE_INTEREST, LEGAL_OBLIGATION, VITAL_INTEREST }
}
