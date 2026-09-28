package org.openfoundry.foundation.sync;

import org.openfoundry.foundation.spi.schema.PropertyValues;

import java.util.Map;
import java.util.Objects;

/** Lifecycle wrapper for a read-through overlay connector; no overlay value enters StorageProvider. */
public final class ManagedOverlay implements AutoCloseable {
    private final ManagedConnector connector;
    private final OverlayEngine engine;

    public ManagedOverlay(DatasourceMapping mapping, ConnectorRegistry registry) {
        Objects.requireNonNull(mapping);
        Objects.requireNonNull(registry);
        if (mapping.sync().mode() != DatasourceMapping.Mode.OVERLAY) {
            throw new IllegalArgumentException("Managed overlay requires OVERLAY mapping mode");
        }
        if (Boolean.TRUE.equals(mapping.sync().writeback())) {
            throw new UnsupportedOperationException("Overlay writeback requires an explicit source mutation policy");
        }
        var plugin = registry.get(mapping.connector());
        if (plugin == null) throw new IllegalArgumentException("Unknown connector: " + mapping.connector());
        connector = ConnectorRegistry.create(plugin, mapping);
        try {
            connector.initialize(mapping.connection());
            if (!connector.capabilities().fullExtract()) throw new IllegalArgumentException("Overlay connector lacks full extraction");
            engine = new OverlayEngine(mapping, connector);
        } catch (RuntimeException | Error failure) {
            connector.close();
            throw failure;
        }
    }

    public OverlayEngine.OverlayObject get(Map<String, Object> key) {
        return engine.get(key);
    }

    public int cacheSize() {
        return engine.cacheSize();
    }

    public void clearCache() {
        engine.clearCache();
    }

    @Override
    public void close() {
        try {
            engine.close();
        } finally {
            connector.close();
        }
    }
}
