package org.openfoundry.foundation.sync;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Trusted factories only; registration does not connect to a source or expand environment variables. */
public final class ConnectorRegistry {
    public record Plugin(String name, String version, String description, String partition, Function<DatasourceMapping, ManagedConnector> factory) {
        public Plugin(String name, String version, String description, Function<DatasourceMapping, ManagedConnector> factory) {
            this(name, version, description, name + "-poll-v1", factory);
        }

        public Plugin {
            if (name == null || name.isBlank() || version == null || version.isBlank() || partition == null || partition.isBlank()) {
                throw new IllegalArgumentException("Connector name and version are required");
            }
            Objects.requireNonNull(factory);
        }
    }

    private final Map<String, Plugin> plugins = new LinkedHashMap<>();

    public synchronized void register(Plugin plugin) {
        Objects.requireNonNull(plugin);
        if (plugins.putIfAbsent(plugin.name(), plugin) != null) {
            throw new IllegalArgumentException("Connector is already registered: " + plugin.name());
        }
    }

    public synchronized boolean unregister(String name) {
        return plugins.remove(name) != null;
    }

    public synchronized Plugin get(String name) {
        return plugins.get(name);
    }

    public synchronized List<String> list() {
        return List.copyOf(plugins.keySet());
    }

    public ManagedConnector create(DatasourceMapping mapping) {
        Plugin plugin = get(mapping.connector());
        if (plugin == null) {
            throw new IllegalArgumentException("Unknown connector: " + mapping.connector());
        }
        return create(plugin, mapping);
    }

    static ManagedConnector create(Plugin plugin, DatasourceMapping mapping) {
        ManagedConnector connector = Objects.requireNonNull(plugin.factory().apply(mapping));
        if (!connector.name().equals(mapping.datasource()) || !connector.version().equals(plugin.version())) {
            connector.close();
            throw new IllegalStateException("Connector factory identity/version disagrees with registration");
        }
        return connector;
    }

    /** Register the built-in HTTP JSON adapter; custom trusted factories may supply scoped credentials. */
    public static ConnectorRegistry rest() {
        var registry = new ConnectorRegistry();
        registry.register(new Plugin("rest", RestSourceConnector.VERSION, "Paginated JSON REST extraction",
                mapping -> new RestSourceConnector(mapping.datasource(), mapping.datasource())));
        return registry;
    }

    /** The host owns credentials, endpoint resolution and the DataSource pool lifecycle. */
    public static ConnectorRegistry jdbc(Function<DatasourceMapping.Connection, DataSource> resolver) {
        Objects.requireNonNull(resolver);
        var registry = new ConnectorRegistry();
        registry.register(new Plugin("jdbc", JdbcSourceConnector.VERSION, "JDBC keyset extraction and timestamp polling",
                mapping -> new JdbcSourceConnector(mapping.datasource(), mapping.datasource(),
                        Objects.requireNonNull(resolver.apply(mapping.connection())))));
        return registry;
    }
}
