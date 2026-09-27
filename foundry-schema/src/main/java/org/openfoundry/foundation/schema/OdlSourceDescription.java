package org.openfoundry.foundation.schema;

import java.util.Set;

/** Source metadata collected before cross-file interface and type resolution. */
public record OdlSourceDescription(String namespace, String version, Set<String> declarations, Set<String> references) {
    public OdlSourceDescription {
        declarations = Set.copyOf(declarations);
        references = Set.copyOf(references);
    }
}
