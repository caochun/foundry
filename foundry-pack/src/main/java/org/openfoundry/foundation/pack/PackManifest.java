package org.openfoundry.foundation.pack;

import org.openfoundry.foundation.spi.schema.PropertyValues;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record PackManifest(String name, String version, String namespace,
                           Map<String, String> dependencies, List<String> schemaFiles, List<String> actionFiles,
                           List<String> permissionFiles, List<String> seedFiles, List<String> connectorFiles,
                           Set<String> capabilities, Map<String, Object> metadata) {
    public PackManifest(String name, String version, String namespace, Map<String, String> dependencies,
                        List<String> schemaFiles, List<String> actionFiles) {
        this(name, version, namespace, dependencies, schemaFiles, actionFiles, List.of(), List.of(), List.of(), Set.of(), Map.of());
    }

    public PackManifest {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("pack name must not be blank");
        if (version == null || version.isBlank()) throw new IllegalArgumentException("pack version must not be blank");
        if (namespace == null || !namespace.matches("[A-Za-z][A-Za-z0-9_.-]*")) throw new IllegalArgumentException("pack namespace must not be blank");
        dependencies = Map.copyOf(dependencies);
        schemaFiles = List.copyOf(schemaFiles);
        actionFiles = List.copyOf(actionFiles);
        permissionFiles = List.copyOf(permissionFiles);
        seedFiles = List.copyOf(seedFiles);
        connectorFiles = List.copyOf(connectorFiles);
        capabilities = Set.copyOf(capabilities);
        metadata = PropertyValues.immutableMap(metadata);
    }
}
