package org.openfoundry.foundation.pack;

import org.openfoundry.foundation.security.FieldPolicy;
import org.openfoundry.foundation.spi.schema.PropertyValues;

import java.util.List;
import java.util.Map;

/** Parsed assets retain their pack namespace and relative source path. Loading performs no external writes. */
public record PackAssets(Map<String, FieldPolicy> fieldPolicies, List<PermissionSource> permissions,
                         List<SeedBatch> seeds, List<ConnectorDefinition> connectors) {
    public PackAssets {
        fieldPolicies = Map.copyOf(fieldPolicies);
        permissions = List.copyOf(permissions);
        seeds = List.copyOf(seeds);
        connectors = List.copyOf(connectors);
    }

    public static PackAssets empty() { return new PackAssets(Map.of(), List.of(), List.of(), List.of()); }
    public record PermissionSource(String namespace, String path, String dsl) {}
    public record SeedObject(String type, String reference, Map<String, Object> fields) {
        public SeedObject { fields = PropertyValues.immutableMap(fields); }
    }
    public record SeedLink(String type, String from, String to, Map<String, Object> fields) {
        public SeedLink { fields = PropertyValues.immutableMap(fields); }
    }
    public record SeedBatch(String namespace, String path, List<SeedObject> objects, List<SeedLink> links) {
        public SeedBatch { objects = List.copyOf(objects); links = List.copyOf(links); }
    }
    public record ConnectorDefinition(String namespace, String path, String connector, Map<String, Object> config) {
        public ConnectorDefinition {
            if (config.containsKey("mapping") || config.containsKey("datasource")) {
                var declaration = new org.openfoundry.foundation.sync.MappingConfigParser().parse(config);
                if (!connector.equals(declaration.connector())) throw new IllegalArgumentException("Connector declaration disagrees with its asset identity");
            }
            config = PropertyValues.immutableMap(config);
        }
        public java.util.Optional<org.openfoundry.foundation.sync.DatasourceMapping> datasourceMapping() {
            return config.containsKey("mapping") || config.containsKey("datasource")
                    ? java.util.Optional.of(new org.openfoundry.foundation.sync.MappingConfigParser().parse(config)) : java.util.Optional.empty();
        }
    }
}
