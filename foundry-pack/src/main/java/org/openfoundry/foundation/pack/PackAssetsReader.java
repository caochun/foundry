package org.openfoundry.foundation.pack;

import org.openfoundry.foundation.security.FieldPolicy;
import org.openfoundry.foundation.spi.schema.OntologySchema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class PackAssetsReader {
    PackAssets read(Path root, PackManifest manifest, Map<String, String> files) throws IOException {
        var policies = new LinkedHashMap<String, FieldPolicy>();
        String convention = "permissions/field-permissions.yaml";
        if (Files.exists(root.resolve(convention)) || Files.isSymbolicLink(root.resolve(convention))) {
            for (var raw : PackFiles.list(PackFiles.yaml(read(root, convention, files)), convention)) {
                var value = PackFiles.map(raw, "field permission");
                requireKeys(value, Set.of("objectType", "alwaysVisible", "fieldsByRelation"));
                var grants = new LinkedHashMap<String, Set<String>>();
                PackFiles.optionalMap(value.get("fieldsByRelation"), "fieldsByRelation").forEach((role, fields) -> {
                    if (role.isBlank()) throw new PackLoadException("Field permission role must not be blank");
                    grants.put(role, Set.copyOf(PackFiles.strings(fields, "role fields")));
                });
                String type = PackFiles.text(value, "objectType");
                var policy = FieldPolicy.storedFields(Set.copyOf(PackFiles.strings(value.get("alwaysVisible"), "alwaysVisible")), grants);
                if (policies.putIfAbsent(type, policy) != null) throw new PackLoadException("Duplicate field policy: " + type);
            }
        }
        var permissions = new ArrayList<PackAssets.PermissionSource>();
        for (String file : manifest.permissionFiles()) {
            if (!file.endsWith(".fga")) throw new PackLoadException("Unsupported permission asset: " + file);
            String source = read(root, file, files);
            if (source.isBlank()) throw new PackLoadException("Empty permission asset: " + file);
            permissions.add(new PackAssets.PermissionSource(manifest.namespace(), file, source));
        }
        var seeds = new ArrayList<PackAssets.SeedBatch>();
        for (String file : manifest.seedFiles()) {
            var raw = PackFiles.map(PackFiles.yaml(read(root, file, files)), "seed file");
            requireKeys(raw, Set.of("objects", "links"));
            var objects = new ArrayList<PackAssets.SeedObject>();
            for (var entry : PackFiles.list(raw.get("objects"), "seed objects")) {
                var value = PackFiles.map(entry, "seed object");
                requireKeys(value, Set.of("type", "ref", "fields"));
                objects.add(new PackAssets.SeedObject(PackFiles.text(value, "type"), value.containsKey("ref") ? PackFiles.text(value, "ref") : null,
                        PackFiles.optionalMap(value.get("fields"), "seed fields")));
            }
            var links = new ArrayList<PackAssets.SeedLink>();
            for (var entry : PackFiles.list(raw.get("links"), "seed links")) {
                var value = PackFiles.map(entry, "seed link");
                requireKeys(value, Set.of("type", "from", "to", "fields"));
                links.add(new PackAssets.SeedLink(PackFiles.text(value, "type"), PackFiles.text(value, "from"), PackFiles.text(value, "to"),
                        PackFiles.optionalMap(value.get("fields"), "seed link fields")));
            }
            seeds.add(new PackAssets.SeedBatch(manifest.namespace(), file, objects, links));
        }
        var connectors = new ArrayList<PackAssets.ConnectorDefinition>();
        for (String file : manifest.connectorFiles()) {
            var config = PackFiles.map(PackFiles.yaml(read(root, file, files)), "connector");
            connectors.add(new PackAssets.ConnectorDefinition(manifest.namespace(), file, PackFiles.text(config, "connector"), config));
        }
        return new PackAssets(policies, permissions, seeds, connectors);
    }

    private static String read(Path root, String path, Map<String, String> files) throws IOException {
        String value = files.get(path);
        if (value == null) {
            value = PackFiles.read(root, path);
            files.put(path, value);
        }
        return value;
    }

    static PackAssets merge(List<PackAssets> inputs) {
        var policies = new LinkedHashMap<String, FieldPolicy>();
        var permissions = new ArrayList<PackAssets.PermissionSource>();
        var seeds = new ArrayList<PackAssets.SeedBatch>();
        var connectors = new ArrayList<PackAssets.ConnectorDefinition>();
        for (var input : inputs) {
            input.fieldPolicies().forEach((name, policy) -> {
                if (policies.putIfAbsent(name, policy) != null) throw new PackLoadException("Multiple packs define field policy: " + name);
            });
            permissions.addAll(input.permissions());
            seeds.addAll(input.seeds());
            connectors.addAll(input.connectors());
        }
        return new PackAssets(policies, permissions, seeds, connectors);
    }

    static void validate(PackAssets assets, OntologySchema schema) {
        assets.connectors().forEach(connector -> connector.datasourceMapping().ifPresent(declaration ->
                org.openfoundry.foundation.sync.MappingSchemaValidator.validate(declaration.mapping(), schema)));
        var fields = new LinkedHashMap<String, Set<String>>();
        schema.objectTypes().forEach(type -> fields.put(type.name(), type.properties().stream().map(field -> field.name()).collect(java.util.stream.Collectors.toSet())));
        assets.fieldPolicies().forEach((type, policy) -> {
            var known = fields.get(type);
            if (known == null || !known.containsAll(policy.alwaysVisible())
                    || policy.fieldsByRole().values().stream().anyMatch(grant -> !known.containsAll(grant))) {
                throw new PackLoadException("Field policy references unknown type or non-stored field: " + type);
            }
        });
        var links = schema.linkTypes().stream().map(type -> type.name()).collect(java.util.stream.Collectors.toSet());
        var references = new java.util.HashSet<String>();
        for (var seed : assets.seeds()) {
            for (var object : seed.objects()) {
                if (!fields.containsKey(object.type())) throw new PackLoadException("Unknown seed object type: " + object.type());
                if (object.reference() != null && !references.add(seed.namespace() + ":" + object.reference())) throw new PackLoadException("Duplicate seed reference: " + object.reference());
            }
            for (var link : seed.links()) if (!links.contains(link.type())) throw new PackLoadException("Unknown seed relationship type: " + link.type());
        }
    }

    private static void requireKeys(Map<String, Object> value, Set<String> allowed) {
        for (String name : value.keySet()) if (!allowed.contains(name)) throw new PackLoadException("Unsupported asset field: " + name);
    }
}
