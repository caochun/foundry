package org.openfoundry.foundation.sync;

import org.openfoundry.foundation.spi.schema.PropertyValues;

import java.util.*;

/** Immutable trusted extension registry. Updating code requires an explicit version and a new registry. */
public final class TransformRegistry {
    @FunctionalInterface
    public interface Transform { Object apply(Object value, Map<String, Object> record); }
    public record Registered(String version, Transform transform) {
        public Registered {
            if (version == null || version.isBlank()) throw new IllegalArgumentException("Custom transform version is required");
            Objects.requireNonNull(transform);
        }
    }
    public record Compiled(Transform transform, Map<String, String> customVersions) {
        public Compiled { customVersions = Map.copyOf(customVersions); }
        public Object apply(Object value, Map<String, Object> record) { return PropertyValues.immutableValue(transform.apply(value, record)); }
    }
    private final Map<String, Registered> functions;
    public TransformRegistry() { this(Map.of()); }
    private TransformRegistry(Map<String, Registered> functions) { this.functions = Map.copyOf(functions); }

    public TransformRegistry with(String name, String version, Transform function) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Custom transform name is required");
        var copy = new LinkedHashMap<>(functions);
        if (copy.putIfAbsent(name, new Registered(version, function)) != null) throw new IllegalArgumentException("Custom transform already registered");
        return new TransformRegistry(copy);
    }

    public Compiled compile(String expression) { return MappingTransforms.compile(expression, functions); }
}
