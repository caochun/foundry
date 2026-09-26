package org.openfoundry.foundation.pack;

import org.openfoundry.foundation.actions.ActionManifest;
import org.openfoundry.foundation.actions.ActionManifestParser;
import org.openfoundry.foundation.schema.CompiledOntology;
import org.openfoundry.foundation.schema.OdlParser;
import org.openfoundry.foundation.schema.SchemaCompiler;
import org.openfoundry.foundation.spi.schema.ActionTypeDefinition;
import org.openfoundry.foundation.spi.schema.LinkTypeDefinition;
import org.openfoundry.foundation.spi.schema.ObjectTypeDefinition;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Collection;
import java.util.stream.Collectors;

/** Loads one external Domain Pack deterministically from a directory. */
public final class DomainPackLoader {
    private final OdlParser odlParser;
    private final SchemaCompiler schemaCompiler;
    private final ActionManifestParser actionParser;

    public DomainPackLoader() {
        this(new OdlParser(), new SchemaCompiler(), new ActionManifestParser());
    }

    public DomainPackLoader(OdlParser odlParser, SchemaCompiler schemaCompiler,
                            ActionManifestParser actionParser) {
        this.odlParser = odlParser;
        this.schemaCompiler = schemaCompiler;
        this.actionParser = actionParser;
    }

    public LoadedDomainPack load(Path directory) {
        try {
            PackManifest manifest = readManifest(directory.resolve("pack.yaml"));
            OntologySchema schema = loadSchema(directory, manifest);
            CompiledOntology ontology = schemaCompiler.compile(schema);
            Map<String, ActionManifest> actions = loadActions(directory, manifest);
            validateActions(schema, actions);
            return new LoadedDomainPack(manifest, directory, ontology, actions);
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof PackLoadException packLoadException) throw packLoadException;
            throw new PackLoadException("failed to load Domain Pack " + directory, exception);
        }
    }

    public List<LoadedDomainPack> loadAll(Collection<Path> directories) {
        List<LoadedDomainPack> packs = directories.stream().map(this::load).toList();
        Map<String, String> versions = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        for (LoadedDomainPack pack : packs) {
            if (!names.add(pack.manifest().name())) throw new PackLoadException("duplicate pack name: " + pack.manifest().name());
            if (versions.put(pack.manifest().namespace(), pack.manifest().version()) != null) {
                throw new PackLoadException("duplicate pack namespace: " + pack.manifest().namespace());
            }
        }
        for (LoadedDomainPack pack : packs) {
            for (Map.Entry<String, String> dependency : pack.manifest().dependencies().entrySet()) {
                String version = versions.get(dependency.getKey());
                if (version == null || !satisfies(version, dependency.getValue())) {
                    throw new PackLoadException("pack " + pack.manifest().name() + " requires "
                            + dependency.getKey() + " " + dependency.getValue() + ", loaded " + version);
                }
            }
        }
        return packs;
    }

    private static boolean satisfies(String version, String constraint) {
        String normalized = constraint.trim();
        if (normalized.startsWith(">=")) return compareVersion(version, normalized.substring(2).trim()) >= 0;
        return compareVersion(version, normalized) == 0;
    }

    private static int compareVersion(String left, String right) {
        String[] a = left.split("\\."), b = right.split("\\.");
        for (int i = 0; i < 3; i++) {
            int av = i < a.length ? Integer.parseInt(a[i]) : 0;
            int bv = i < b.length ? Integer.parseInt(b[i]) : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private PackManifest readManifest(Path path) throws IOException {
        if (!Files.exists(path)) throw new PackLoadException("missing pack.yaml: " + path);
        Object loaded = new Yaml().load(Files.readString(path));
        Map<String, Object> root = map(loaded, "pack.yaml must be a mapping");
        Map<String, String> dependencies = new LinkedHashMap<>();
        mapOrEmpty(root.get("dependencies")).forEach((key, value) -> dependencies.put(key, String.valueOf(value)));
        return new PackManifest(string(root, "name"), string(root, "version"), string(root, "namespace"),
                dependencies, strings(root.get("schema")), strings(root.get("actions")));
    }

    private OntologySchema loadSchema(Path directory, PackManifest manifest) throws IOException {
        if (manifest.schemaFiles().isEmpty()) {
            throw new PackLoadException("pack must list at least one schema file: " + manifest.name());
        }
        StringBuilder source = new StringBuilder();
        for (String file : manifest.schemaFiles()) {
            Path path = directory.resolve(file).normalize();
            if (!path.startsWith(directory.normalize())) throw new PackLoadException("schema path escapes pack: " + file);
            source.append(Files.readString(path)).append('\n');
        }
        OntologySchema parsed = odlParser.parse(source.toString());
        if (!parsed.namespace().equals(manifest.namespace())) {
            throw new PackLoadException("schema namespace " + parsed.namespace() + " does not match pack namespace " + manifest.namespace());
        }
        return parsed;
    }

    private Map<String, ActionManifest> loadActions(Path directory, PackManifest manifest) throws IOException {
        Map<String, ActionManifest> actions = new LinkedHashMap<>();
        for (String file : manifest.actionFiles()) {
            Path path = directory.resolve(file).normalize();
            if (!path.startsWith(directory.normalize())) throw new PackLoadException("action path escapes pack: " + file);
            ActionManifest action = actionParser.parse(Files.readString(path));
            if (actions.put(action.action(), action) != null) throw new PackLoadException("duplicate Action: " + action.action());
        }
        return actions;
    }

    private static void validateActions(OntologySchema schema, Map<String, ActionManifest> actions) {
        Map<String, ActionTypeDefinition> definitions = schema.actionTypes().stream()
                .collect(LinkedHashMap::new, (map, value) -> map.put(value.name(), value), Map::putAll);
        for (String action : definitions.keySet()) {
            if (!actions.containsKey(action)) throw new PackLoadException("missing manifest for Action: " + action);
        }
        for (String action : actions.keySet()) {
            if (!definitions.containsKey(action)) throw new PackLoadException("manifest has no @actionType: " + action);
        }
    }

    private static Map<String, Object> map(Object value, String message) {
        if (!(value instanceof Map<?, ?> raw)) throw new PackLoadException(message);
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static Map<String, Object> mapOrEmpty(Object value) {
        return value == null ? Map.of() : map(value, "expected a mapping");
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String text) || text.isBlank()) throw new PackLoadException("missing manifest field: " + key);
        return text;
    }

    private static List<String> strings(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) throw new PackLoadException("expected a list in pack.yaml");
        return list.stream().map(String::valueOf).toList();
    }
}
