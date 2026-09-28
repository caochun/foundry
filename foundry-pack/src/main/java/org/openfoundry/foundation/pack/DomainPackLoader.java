package org.openfoundry.foundation.pack;

import org.openfoundry.foundation.actions.ActionManifest;
import org.openfoundry.foundation.actions.ActionManifestParser;
import org.openfoundry.foundation.schema.OdlParser;
import org.openfoundry.foundation.schema.OdlSourceDescription;
import org.openfoundry.foundation.schema.SchemaCompiler;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.spi.schema.PropertyValues;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads trusted Pack assets without deployment side effects, then compiles a closed ontology explicitly. */
public final class DomainPackLoader {
    private final OdlParser odlParser;
    private final SchemaCompiler schemaCompiler;
    private final ActionManifestParser actionParser;

    public DomainPackLoader() { this(new OdlParser(), new SchemaCompiler(), new ActionManifestParser()); }

    public DomainPackLoader(OdlParser odlParser, SchemaCompiler schemaCompiler, ActionManifestParser actionParser) {
        this.odlParser = odlParser;
        this.schemaCompiler = schemaCompiler;
        this.actionParser = actionParser;
    }

    /** Standalone inspection; dependencies must be supplied to loadBundle for cross-Pack type resolution. */
    public LoadedDomainPack load(Path directory) {
        try { return compileSingle(read(directory)); }
        catch (IOException | RuntimeException failure) { throw failure(directory, failure); }
    }

    /** Legacy list of independent compiled units. Use loadBundle when declarations reference other Packs. */
    public List<LoadedDomainPack> loadAll(Collection<Path> directories) {
        var inputs = readAll(directories);
        var graph = new PackGraph(inputs.values().stream().map(Input::manifest).toList());
        return graph.ordered().stream().map(namespace -> {
            var input = inputs.get(namespace);
            try { return compileSingle(input); }
            catch (RuntimeException invalid) { throw failure(input.root, invalid); }
        }).toList();
    }

    public LoadedPackBundle loadBundle(Collection<Path> directories) {
        try { return composeBundle(directories); }
        catch (RuntimeException invalid) {
            if (invalid instanceof PackLoadException failure) throw failure;
            throw new PackLoadException("Failed to compile pack bundle", invalid);
        }
    }

    private LoadedPackBundle composeBundle(Collection<Path> directories) {
        if (directories.isEmpty()) throw new PackLoadException("A bundle requires at least one pack");
        var inputs = readAll(directories);
        var graph = new PackGraph(inputs.values().stream().map(Input::manifest).toList());
        var ordered = graph.ordered().stream().map(inputs::get).toList();
        var owners = new LinkedHashMap<String, String>();
        for (var input : ordered) {
            for (String type : input.description.declarations()) {
                String previous = owners.putIfAbsent(type, input.manifest.namespace());
                if (previous != null && !PropertyValues.SCALARS.contains(type)) throw new PackLoadException("Type declared by multiple packs: " + type);
            }
        }
        for (var input : ordered) {
            var references = new java.util.HashSet<>(input.description.references());
            references.addAll(input.assets.fieldPolicies().keySet());
            input.assets.seeds().forEach(batch -> {
                batch.objects().forEach(object -> references.add(object.type()));
                batch.links().forEach(link -> references.add(link.type()));
            });
            input.assets.connectors().forEach(connector -> connector.datasourceMapping().ifPresent(declaration -> {
                references.add(declaration.mapping().objectType());
                declaration.mapping().links().forEach(link -> {
                    references.add(link.linkType());
                    references.add(link.toType());
                });
            }));
            input.actions.values().forEach(action -> action.effects().forEach(effect -> {
                if (effect instanceof ActionManifest.CreateObject object) references.add(object.objectType());
                if (effect instanceof ActionManifest.CreateLink link) references.add(link.linkType());
                if (effect instanceof ActionManifest.DeleteLink link) references.add(link.linkType());
            }));
            for (String reference : references) {
                if (PropertyValues.SCALARS.contains(reference)) continue;
                String owner = owners.get(reference);
                if (owner == null || !graph.visible(input.manifest.namespace(), owner)) {
                    throw new PackLoadException(input.manifest.namespace() + " references unavailable type " + reference + " (declare its pack dependency)");
                }
            }
        }
        var contents = new LinkedHashMap<String, Object>();
        for (var input : ordered) {
            var files = new LinkedHashMap<String, Object>(input.files);
            contents.put(input.manifest.namespace(), files);
        }
        String digest = digest(PropertyValues.canonical(contents));
        var schema = odlParser.compose("openfoundry.bundle", "0.0.0+" + digest, ordered.stream().map(Input::source).toList());
        var ontology = schemaCompiler.compile(schema);
        var actions = new LinkedHashMap<String, ActionManifest>();
        for (var input : ordered) input.actions.forEach((name, action) -> {
            if (actions.putIfAbsent(name, action) != null) throw new PackLoadException("Duplicate action manifest: " + name);
            if (!input.manifest.namespace().equals(owners.get(name))) throw new PackLoadException("Action implementation belongs to another pack: " + name);
        });
        validateActions(schema, actions);
        var assets = PackAssetsReader.merge(ordered.stream().map(Input::assets).toList());
        PackAssetsReader.validate(assets, schema);
        var capabilities = ordered.stream().flatMap(input -> input.manifest.capabilities().stream()).collect(java.util.stream.Collectors.toSet());
        return new LoadedPackBundle(ordered.stream().map(Input::manifest).toList(), ontology, owners, actions, assets, capabilities, digest);
    }

    private LoadedDomainPack compileSingle(Input input) {
        var schema = odlParser.parse(input.source);
        var ontology = schemaCompiler.compile(schema);
        validateActions(schema, input.actions);
        PackAssetsReader.validate(input.assets, schema);
        return new LoadedDomainPack(input.manifest, input.root, ontology, input.actions, input.assets);
    }

    private Map<String, Input> readAll(Collection<Path> directories) {
        var inputs = new LinkedHashMap<String, Input>();
        for (var directory : directories) {
            try {
                var input = read(directory);
                if (inputs.putIfAbsent(input.manifest.namespace(), input) != null) throw new PackLoadException("Duplicate pack namespace: " + input.manifest.namespace());
            } catch (IOException | RuntimeException failure) { throw failure(directory, failure); }
        }
        return inputs;
    }

    private Input read(Path directory) throws IOException {
        Path root = directory.toRealPath();
        String manifestSource = PackFiles.read(root, "pack.yaml");
        var raw = PackFiles.map(PackFiles.yaml(manifestSource), "pack.yaml");
        var known = Set.of("name", "version", "namespace", "dependencies", "schema", "actions", "permissions", "seed", "connectors", "capabilities", "description", "provides");
        for (String key : raw.keySet()) if (!known.contains(key) && !key.startsWith("x-")) throw new PackLoadException("Unknown pack field: " + key);
        var metadata = new LinkedHashMap<String, Object>();
        raw.forEach((key, value) -> { if (key.startsWith("x-") || key.equals("description") || key.equals("provides")) metadata.put(key, value); });
        var dependencies = new LinkedHashMap<String, String>();
        PackFiles.optionalMap(raw.get("dependencies"), "dependencies").forEach((namespace, value) -> {
            if (!(value instanceof String text) || text.isBlank()) throw new PackLoadException("Dependency constraint must be text");
            dependencies.put(namespace, text);
        });
        var manifest = new PackManifest(PackFiles.text(raw, "name"), PackFiles.text(raw, "version"), PackFiles.text(raw, "namespace"),
                dependencies, PackFiles.strings(raw.get("schema"), "schema"), PackFiles.strings(raw.get("actions"), "actions"),
                PackFiles.strings(raw.get("permissions"), "permissions"), PackFiles.strings(raw.get("seed"), "seed"),
                PackFiles.strings(raw.get("connectors"), "connectors"), Set.copyOf(PackFiles.strings(raw.get("capabilities"), "capabilities")), metadata);
        PackGraph.Version.parse(manifest.version());
        if (manifest.schemaFiles().isEmpty()) throw new PackLoadException("Pack requires at least one schema file");
        var files = new LinkedHashMap<String, String>();
        files.put("pack.yaml", manifestSource);
        var source = new StringBuilder();
        for (String file : manifest.schemaFiles()) {
            String content = PackFiles.read(root, file);
            files.put(file, content);
            source.append(content).append('\n');
        }
        var description = odlParser.describe(source.toString());
        if (!description.namespace().equals(manifest.namespace()) || !description.version().equals(manifest.version())) {
            throw new PackLoadException("Schema namespace/version does not match pack.yaml: " + manifest.name());
        }
        var actions = new LinkedHashMap<String, ActionManifest>();
        for (String file : manifest.actionFiles()) {
            String content = PackFiles.read(root, file);
            files.put(file, content);
            var action = actionParser.parse(content);
            if (actions.putIfAbsent(action.action(), action) != null) throw new PackLoadException("Duplicate action manifest: " + action.action());
        }
        var assets = new PackAssetsReader().read(root, manifest, files);
        return new Input(root, manifest, source.toString(), description, Map.copyOf(actions), assets, Map.copyOf(files));
    }

    private static void validateActions(OntologySchema schema, Map<String, ActionManifest> actions) {
        var declared = schema.actionTypes().stream().map(type -> type.name()).collect(java.util.stream.Collectors.toSet());
        if (!declared.equals(actions.keySet())) throw new PackLoadException("Action declarations and implementations differ: " + declared + " / " + actions.keySet());
    }

    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static PackLoadException failure(Path directory, Exception failure) {
        return failure instanceof PackLoadException loaded ? loaded : new PackLoadException("Failed to load Domain Pack " + directory, failure);
    }

    private record Input(Path root, PackManifest manifest, String source, OdlSourceDescription description,
                         Map<String, ActionManifest> actions, PackAssets assets, Map<String, String> files) {}
}
