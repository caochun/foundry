package org.openfoundry.foundation.api;

/** Startup configuration, never accepted from an API request. */
public enum AuthorizationMode {
    /** Existing Java contract: action relation on ActionType and every referenced resource. */
    STRICT_RESOURCES,
    /** ODL target action permission, participant visibility and explicit editor checks for other existing mutation targets. */
    ONTOLOGY_TARGETS
}
