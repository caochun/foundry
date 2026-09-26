package org.openfoundry.foundation.pack;

import java.util.List;
import java.util.Map;

public record PackManifest(String name, String version, String namespace,
                           Map<String, String> dependencies, List<String> schemaFiles,
                           List<String> actionFiles) {
    public PackManifest {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("pack name must not be blank");
        if (version == null || version.isBlank()) throw new IllegalArgumentException("pack version must not be blank");
        if (namespace == null || namespace.isBlank()) throw new IllegalArgumentException("pack namespace must not be blank");
        dependencies = Map.copyOf(dependencies);
        schemaFiles = List.copyOf(schemaFiles);
        actionFiles = List.copyOf(actionFiles);
    }
}
