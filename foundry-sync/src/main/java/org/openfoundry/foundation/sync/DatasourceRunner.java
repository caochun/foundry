package org.openfoundry.foundation.sync;

import org.openfoundry.foundation.spi.IngestionCheckpoint;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.StorageProvider;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/** One explicitly requested batch or polling pass. Scheduling and CDC/overlay use separate runtime contracts. */
public final class DatasourceRunner {
    private final StorageProvider storage;
    private final ConnectorRegistry registry;
    private final SyncAuthorizer authorizer;
    private final TransformRegistry transforms;
    private final Map<String, Integer> sourcePriorities;

    public DatasourceRunner(StorageProvider storage, ConnectorRegistry registry, SyncAuthorizer authorizer) {
        this(storage, registry, authorizer, new TransformRegistry(), Map.of());
    }

    public DatasourceRunner(StorageProvider storage, ConnectorRegistry registry, SyncAuthorizer authorizer,
                            TransformRegistry transforms, Map<String, Integer> sourcePriorities) {
        this.storage = Objects.requireNonNull(storage);
        this.registry = Objects.requireNonNull(registry);
        this.authorizer = Objects.requireNonNull(authorizer);
        this.transforms = Objects.requireNonNull(transforms);
        this.sourcePriorities = Map.copyOf(sourcePriorities);
    }

    public MaterializedSyncService.SyncResult runOnce(DatasourceMapping mapping, RequestContext context) {
        return runOnce(mapping, context, ManagedConnector.ExtractOptions.defaults());
    }

    public MaterializedSyncService.SyncResult runOnce(DatasourceMapping mapping, RequestContext context, ManagedConnector.ExtractOptions options) {
        validateMode(mapping);
        Objects.requireNonNull(options);
        ConnectorRegistry.Plugin plugin = requirePlugin(mapping);
        MaterializedSyncService service = service(mapping, plugin);
        // Checks authority, storage support, custom functions and configuration drift before creating a source resource.
        IngestionCheckpoint checkpoint = service.checkpoint(mapping.datasource(), mapping.mapping(), partition(plugin), context);
        Integer declaredRate = mapping.sync().maxRecordsPerSecond();
        Integer rate = options.maxRecordsPerSecond();
        if (declaredRate != null) {
            rate = rate == null ? declaredRate : Math.min(rate, declaredRate);
        }
        var extraction = new ManagedConnector.ExtractOptions(options.batchSize(), rate, options.queryTimeout());
        Connector adapter = new Connector() {
            @Override
            public String name() {
                return mapping.datasource();
            }

            @Override
            public Stream<SourceRecord> read(SourceQuery ignored) {
                ManagedConnector source = ConnectorRegistry.create(plugin, mapping);
                try {
                    source.initialize(mapping.connection());
                    if (!source.partition().equals(partition(plugin))) {
                        throw new IllegalStateException("Connector partition disagrees with the runtime binding");
                    }
                    Stream<SourceRecord> records;
                    if (mapping.sync().mode() == DatasourceMapping.Mode.POLLING) {
                        if (!source.capabilities().incrementalExtract()) {
                            throw new IllegalArgumentException("Source does not support incremental extraction");
                        }
                        records = source.incrementalExtract(ManagedConnector.Cursor.from(checkpoint), extraction);
                    } else {
                        if (!source.capabilities().fullExtract()) {
                            throw new IllegalArgumentException("Source does not support full extraction");
                        }
                        records = source.fullExtract(extraction);
                    }
                    return records.onClose(source::close);
                } catch (RuntimeException | Error failure) {
                    try {
                        source.close();
                    } catch (RuntimeException closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                    throw failure;
                }
            }
        };
        return service.sync(adapter, new SourceQuery(mapping.connection().table(), Map.of()), mapping.mapping(), context);
    }

    public IngestionCheckpoint checkpoint(DatasourceMapping mapping, RequestContext context) {
        validateMode(mapping);
        var plugin = requirePlugin(mapping);
        return service(mapping, plugin).checkpoint(mapping.datasource(), mapping.mapping(), partition(plugin), context);
    }

    private MaterializedSyncService service(DatasourceMapping mapping, ConnectorRegistry.Plugin plugin) {
        var strategy = mapping.sync().conflictResolution() == null ? ConflictResolver.Strategy.LAST_WRITE_WINS : mapping.sync().conflictResolution();
        var conflicts = new ConflictResolver(strategy, Map.of(), sourcePriorities);
        var declaration = Map.<String, Object>of("datasource", mapping.datasource(), "connector", plugin.name(), "version", plugin.version(),
                "url", mapping.connection().url(), "table", mapping.connection().table(), "properties", mapping.connection().properties(),
                "mode", mapping.sync().mode().name(), "partition", plugin.partition());
        return new MaterializedSyncService(storage, conflicts).withAuthorization(authorizer).withTransforms(transforms)
                .withSourceConfiguration(declaration);
    }

    private ConnectorRegistry.Plugin requirePlugin(DatasourceMapping mapping) {
        var plugin = registry.get(mapping.connector());
        if (plugin == null) {
            throw new IllegalArgumentException("Unknown connector: " + mapping.connector());
        }
        return plugin;
    }

    private static String partition(ConnectorRegistry.Plugin plugin) {
        // Partition is independent of table/configuration, so changing a table cannot silently reset a pipeline checkpoint.
        return plugin.partition();
    }

    private static void validateMode(DatasourceMapping mapping) {
        if (mapping.sync().mode() != DatasourceMapping.Mode.BATCH && mapping.sync().mode() != DatasourceMapping.Mode.POLLING) {
            throw new UnsupportedOperationException("runOnce supports BATCH/POLLING; CDC and OVERLAY require their own runtimes");
        }
        if (Boolean.TRUE.equals(mapping.sync().writeback()) || mapping.sync().cacheStrategy() != null || mapping.sync().cacheTTL() != null) {
            throw new UnsupportedOperationException("Materialized extraction does not execute writeback or overlay caching declarations");
        }
    }
}
