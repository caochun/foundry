package org.openfoundry.foundation.pack;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.resolver.Resolver;
import org.yaml.snakeyaml.nodes.Tag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PackFiles {
    private PackFiles() {}

    static Path path(Path root, String relative) throws IOException {
        var declared = Path.of(relative);
        if (declared.isAbsolute()) throw new PackLoadException("Pack paths must be relative: " + relative);
        var resolved = root.resolve(declared).normalize();
        if (!resolved.startsWith(root)) throw new PackLoadException("Asset escapes pack: " + relative);
        var real = resolved.toRealPath();
        if (!real.startsWith(root)) throw new PackLoadException("Asset escapes pack: " + relative);
        if (!Files.isRegularFile(real)) throw new PackLoadException("Asset is not a file: " + relative);
        return real;
    }

    static String read(Path root, String relative) throws IOException {
        return Files.readString(path(root, relative));
    }

    static Object yaml(String source) {
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        // Keep YAML date/time text as text so the ontology's scalar validator determines its meaning.
        var resolver = new Resolver() {
            @Override public void addImplicitResolver(Tag tag, java.util.regex.Pattern regexp, String first, int limit) {
                if (!tag.equals(Tag.TIMESTAMP)) super.addImplicitResolver(tag, regexp, first, limit);
            }
        };
        return new Yaml(new SafeConstructor(options), new org.yaml.snakeyaml.representer.Representer(new org.yaml.snakeyaml.DumperOptions()),
                new org.yaml.snakeyaml.DumperOptions(), options, resolver).load(source);
    }

    static Map<String, Object> map(Object value, String label) {
        if (!(value instanceof Map<?, ?> raw)) throw new PackLoadException(label + " must be a mapping");
        var result = new LinkedHashMap<String, Object>();
        raw.forEach((key, item) -> {
            if (!(key instanceof String name)) throw new PackLoadException(label + " keys must be strings");
            result.put(name, item);
        });
        return result;
    }

    static Map<String, Object> optionalMap(Object value, String label) { return value == null ? Map.of() : map(value, label); }

    static List<?> list(Object value, String label) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) throw new PackLoadException(label + " must be a list");
        return list;
    }

    static String text(Map<String, Object> value, String field) {
        if (!(value.get(field) instanceof String text) || text.isBlank()) throw new PackLoadException("Missing string field: " + field);
        return text;
    }

    static List<String> strings(Object value, String label) {
        var result = list(value, label).stream().map(item -> {
            if (!(item instanceof String text) || text.isBlank()) throw new PackLoadException(label + " must contain nonempty strings");
            return text;
        }).toList();
        if (result.stream().distinct().count() != result.size()) throw new PackLoadException("Duplicate entry in " + label);
        return result;
    }
}
