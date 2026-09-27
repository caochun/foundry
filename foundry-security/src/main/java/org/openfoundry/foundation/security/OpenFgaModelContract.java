package org.openfoundry.foundation.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.openfoundry.foundation.spi.schema.OntologySchema;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Validates the model's declared relations; OpenFGA remains the authority for evaluating usersets. */
public final class OpenFgaModelContract {
    private final Map<String, Set<String>> relations;
    private final Map<String, String> typeNames;

    public OpenFgaModelContract(String modelJson, Map<String, String> typeNames) {
        this.typeNames = Map.copyOf(typeNames);
        if (new HashSet<>(typeNames.values()).size() != typeNames.size()) throw new IllegalArgumentException("OpenFGA type aliases must be one-to-one");
        var parsed = new LinkedHashMap<String, Set<String>>();
        try {
            var model = new ObjectMapper().readTree(modelJson);
            if (!model.path("schema_version").asText().equals("1.1") || !model.path("type_definitions").isArray()) {
                throw new IllegalArgumentException("Expected an OpenFGA schema 1.1 authorization model");
            }
            for (var type : model.path("type_definitions")) {
                String name = type.path("type").asText();
                if (name.isBlank() || parsed.containsKey(name)) throw new IllegalArgumentException("Duplicate or missing OpenFGA type");
                var names = new HashSet<String>();
                type.path("relations").fieldNames().forEachRemaining(names::add);
                parsed.put(name, Set.copyOf(names));
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalArgumentException("Invalid OpenFGA model JSON", invalid);
        }
        relations = Map.copyOf(parsed);
    }

    public void requireOntologyTargets(OntologySchema schema) {
        var objects = schema.objectTypes().stream().map(type -> type.name()).collect(java.util.stream.Collectors.toSet());
        objects.forEach(type -> requireRelation(type, "viewer"));
        for (var action : schema.actionTypes()) {
            if (action.permission() == null) continue; // Such an action cannot be registered as executable by the API.
            String target = action.parameters().stream().map(parameter -> parameter.type().replace("[", "").replace("]", "").replace("!", ""))
                    .filter(objects::contains).findFirst().orElse("ActionType");
            requireRelation(target, action.permission());
        }
    }

    public void requireRelation(String ontologyType, String relation) {
        String type = typeNames.get(ontologyType);
        if (type == null || !relations.getOrDefault(type, Set.of()).contains(relation)) {
            throw new IllegalArgumentException("OpenFGA model is missing " + ontologyType + "." + relation);
        }
    }

    public static Map<String, String> typeNames(OntologySchema schema) {
        var result = new LinkedHashMap<String, String>();
        var names = new HashSet<String>();
        names.add("user");
        names.add("action_type");
        for (var type : schema.objectTypes()) {
            String normalized = type.name().replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
                    .replaceAll("([a-z\\d])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
            if (!names.add(normalized)) throw new IllegalArgumentException("OpenFGA type name collision: " + type.name());
            result.put(type.name(), normalized);
        }
        result.put("ActionType", "action_type");
        return Map.copyOf(result);
    }
}
