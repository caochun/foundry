package org.openfoundry.foundation.security;

import java.util.*;

public record ConsentConfiguration(Set<String> subjectTypes, String purpose, Set<String> recordablePurposes,
                                   Set<String> recorderRoles, Exemption exemption) {
    public ConsentConfiguration(Set<String> subjectTypes, String purpose) {
        this(subjectTypes, purpose, Set.of(purpose), Set.of("admin"), null);
    }
    public ConsentConfiguration {
        subjectTypes = Set.copyOf(subjectTypes);
        recordablePurposes = Set.copyOf(recordablePurposes);
        recorderRoles = Set.copyOf(recorderRoles);
        if (subjectTypes.isEmpty()) throw new IllegalArgumentException("Consent subject types are required");
        subjectTypes.forEach(ConsentConfiguration::text);
        recordablePurposes.forEach(ConsentConfiguration::text);
        recorderRoles.forEach(ConsentConfiguration::text);
        text(purpose);
        if (!recordablePurposes.contains(purpose)) throw new IllegalArgumentException("The enforced purpose must be recordable");
        if (exemption != null && !recordablePurposes.contains(exemption.purpose())) throw new IllegalArgumentException("The exemption purpose must be recordable");
    }
    public record Exemption(String purpose, String relation) {
        public Exemption { text(purpose); text(relation); }
    }
    static void text(String value) {
        if (value == null || value.isBlank() || value.length() > 255) throw new IllegalArgumentException("Consent configuration values must be nonblank");
    }
}
