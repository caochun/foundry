package org.openfoundry.foundation.schema;

import graphql.language.Argument;
import graphql.language.Definition;
import graphql.language.DirectivesContainer;
import graphql.language.Directive;
import graphql.language.Document;
import graphql.language.FieldDefinition;
import graphql.language.NonNullType;
import graphql.language.SchemaDefinition;
import graphql.language.SchemaExtensionDefinition;
import graphql.language.StringValue;
import graphql.language.Type;
import graphql.language.TypeName;
import graphql.parser.Parser;
import org.openfoundry.foundation.spi.schema.ActionParameter;
import org.openfoundry.foundation.spi.schema.ActionTypeDefinition;
import org.openfoundry.foundation.spi.schema.Cardinality;
import org.openfoundry.foundation.spi.schema.LinkTypeDefinition;
import org.openfoundry.foundation.spi.schema.ObjectTypeDefinition;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.spi.schema.PropertyDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import org.openfoundry.foundation.spi.schema.InterfaceDefinition;
import org.openfoundry.foundation.spi.schema.LinkFieldDefinition;
import org.openfoundry.foundation.spi.schema.ComputedFieldDefinition;
import org.openfoundry.foundation.spi.StorageProvider.Direction;

/** Parses the ODL subset used by Foundation v0.1. ODL is GraphQL SDL plus directives. */
public final class OdlParser {
    private final Parser parser = new Parser();

    public OntologySchema parse(String source) {
        var document = document(source);
        return resolve(document, findNamespace(document));
    }

    public OdlSourceDescription describe(String source) {
        var document = document(source);
        var namespace = findNamespace(document);
        var declarations = new java.util.LinkedHashSet<String>();
        var references = new java.util.LinkedHashSet<String>();
        for (var definition : document.getDefinitions()) {
            if (definition instanceof graphql.language.TypeDefinition<?> type && !declarations.add(type.getName())) {
                throw new SchemaValidationException(List.of("duplicate source type: " + type.getName()));
            }
            collectReferences(definition, references);
        }
        return new OdlSourceDescription(namespace.name(), namespace.version(), declarations, references);
    }

    public OntologySchema compose(String namespace, String version, List<String> sources) {
        var definitions = new ArrayList<Definition<?>>();
        var names = new LinkedHashMap<String, graphql.language.TypeDefinition<?>>();
        for (String source : sources) {
            var document = document(source);
            findNamespace(document);
            for (var definition : document.getDefinitions()) {
                if (definition instanceof SchemaDefinition || definition instanceof SchemaExtensionDefinition) continue;
                if (definition instanceof graphql.language.TypeDefinition<?> type) {
                    var previous = names.putIfAbsent(type.getName(), type);
                    if (previous != null) {
                        if (type instanceof graphql.language.ScalarTypeDefinition scalar && previous instanceof graphql.language.ScalarTypeDefinition oldScalar
                                && Objects.equals(scalarDescription(oldScalar), scalarDescription(scalar))
                                && graphql.language.AstPrinter.printAstCompact(previous).equals(graphql.language.AstPrinter.printAstCompact(type))) continue;
                        throw new SchemaValidationException(List.of("conflicting composed type: " + type.getName()));
                    }
                }
                definitions.add(definition);
            }
        }
        var merged = Document.newDocument();
        definitions.forEach(merged::definition);
        return resolve(merged.build(), new Namespace(namespace, version));
    }

    private Document document(String source) {
        Objects.requireNonNull(source, "source must not be null");
        try { return parser.parseDocument(source); }
        catch (RuntimeException invalid) { throw new SchemaValidationException(List.of("ODL parse error: " + invalid.getMessage())); }
    }

    private static void collectReferences(graphql.language.Node<?> node, Set<String> references) {
        if (node instanceof TypeName type) references.add(type.getName());
        if (node instanceof Directive directive) {
            var names = switch (directive.getName()) {
                case "linkType" -> List.of("from", "to");
                case "link" -> List.of("type");
                default -> List.<String>of();
            };
            for (String name : names) references.add(requiredStringArgument(directive, name));
            if (directive.getName().equals("computed") && directive.getArgument("fn") != null
                    && directive.getArgument("fn").getValue() instanceof StringValue function && function.getValue().equals("countLinks")
                    && directive.getArgument("args") != null && directive.getArgument("args").getValue() instanceof graphql.language.ObjectValue arguments) {
                for (var field : arguments.getObjectFields()) {
                    if (field.getName().equals("type")) {
                        if (field.getValue() instanceof StringValue type) references.add(type.getValue());
                        if (field.getValue() instanceof graphql.language.EnumValue type) references.add(type.getName());
                    }
                }
            }
        }
        for (var child : node.getChildren()) collectReferences(child, references);
    }

    private OntologySchema resolve(Document document, Namespace namespace) {
        List<ObjectTypeDefinition> objects = new ArrayList<>();
        List<LinkTypeDefinition> links = new ArrayList<>();
        List<ActionTypeDefinition> actions = new ArrayList<>();

        var rawInterfaces = new LinkedHashMap<String, graphql.language.InterfaceTypeDefinition>();
        for (Definition<?> definition : document.getDefinitions()) {
            if (definition instanceof graphql.language.InterfaceTypeDefinition type && rawInterfaces.put(type.getName(), type) != null) {
                throw new SchemaValidationException(List.of("duplicate interface: " + type.getName()));
            }
        }
        var interfaces = new LinkedHashMap<String, InterfaceDefinition>();
        for (String name : rawInterfaces.keySet()) resolveInterface(name, rawInterfaces, interfaces, new java.util.HashSet<>());

        for (Definition<?> definition : document.getDefinitions()) {
            if (!(definition instanceof graphql.language.ObjectTypeDefinition object)) {
                continue;
            }
            if (hasDirective(object, "linkType")) {
                links.add(parseLink(object, interfaces));
            } else if (hasDirective(object, "actionType")) {
                actions.add(parseAction(object));
            } else {
                objects.add(parseObject(object, interfaces));
            }
        }

        var enums = new java.util.LinkedHashMap<String, java.util.List<String>>();
        for (Definition<?> definition : document.getDefinitions()) {
            if (definition instanceof graphql.language.EnumTypeDefinition enumeration) {
                var values = enumeration.getEnumValueDefinitions().stream().map(graphql.language.EnumValueDefinition::getName).toList();
                if (enums.put(enumeration.getName(), values) != null) throw new SchemaValidationException(List.of("duplicate enum: " + enumeration.getName()));
            }
        }
        var scalars = new ArrayList<org.openfoundry.foundation.spi.schema.ScalarDefinition>();
        var scalarNames = new java.util.HashSet<String>();
        for (Definition<?> definition : document.getDefinitions()) {
            if (definition instanceof graphql.language.ScalarTypeDefinition scalar) {
                if (!scalarNames.add(scalar.getName())) throw new SchemaValidationException(List.of("duplicate scalar: " + scalar.getName()));
                if (!scalar.getDirectives().isEmpty()) throw new SchemaValidationException(List.of("unsupported scalar directive: " + scalar.getName()));
                try {
                    scalars.add(new org.openfoundry.foundation.spi.schema.ScalarDefinition(scalar.getName(), scalarDescription(scalar)));
                } catch (IllegalArgumentException invalid) {
                    throw new SchemaValidationException(List.of(invalid.getMessage()));
                }
            }
        }
        return new OntologySchema(namespace.name(), namespace.version(), objects, links, actions, enums, List.copyOf(interfaces.values()), scalars);
    }

    private static String scalarDescription(graphql.language.ScalarTypeDefinition scalar) {
        return scalar.getDescription() == null ? null : scalar.getDescription().getContent();
    }

    private static org.openfoundry.foundation.spi.schema.ObjectTypeDefinition parseObject(
            graphql.language.ObjectTypeDefinition definition, Map<String, InterfaceDefinition> interfaces) {
        var parents = ancestors(definition.getImplements(), interfaces);
        var fields = resolveFields(definition.getFieldDefinitions(), parents, interfaces);
        return new org.openfoundry.foundation.spi.schema.ObjectTypeDefinition(definition.getName(), fields, parents,
                inheritedConstraints(definition, parents, interfaces), resolveLinkFields(definition.getFieldDefinitions(), parents, interfaces), resolveComputedFields(definition.getFieldDefinitions(), parents, interfaces));
    }

    private static LinkTypeDefinition parseLink(graphql.language.ObjectTypeDefinition definition, Map<String, InterfaceDefinition> interfaces) {
        Directive directive = requiredDirective(definition, "linkType");
        var parents = ancestors(definition.getImplements(), interfaces);
        return new LinkTypeDefinition(definition.getName(), requiredStringArgument(directive, "from"),
                requiredStringArgument(directive, "to"), Cardinality.valueOf(requiredEnumArgument(directive, "cardinality")),
                resolveFields(definition.getFieldDefinitions(), parents, interfaces), parents, inheritedConstraints(definition, parents, interfaces), resolveLinkFields(definition.getFieldDefinitions(), parents, interfaces), resolveComputedFields(definition.getFieldDefinitions(), parents, interfaces));
    }

    private static InterfaceDefinition resolveInterface(String name, Map<String, graphql.language.InterfaceTypeDefinition> source,
                                                         Map<String, InterfaceDefinition> resolved, Set<String> visiting) {
        if (resolved.containsKey(name)) return resolved.get(name);
        if (!visiting.add(name)) throw new SchemaValidationException(List.of("interface inheritance cycle: " + name));
        var definition = source.get(name);
        if (definition == null) throw new SchemaValidationException(List.of("unknown interface: " + name));
        for (var parent : definition.getImplements()) resolveInterface(((TypeName) parent).getName(), source, resolved, visiting);
        var parents = ancestors(definition.getImplements(), resolved);
        var result = new InterfaceDefinition(name, resolveFields(definition.getFieldDefinitions(), parents, resolved), parents,
                inheritedConstraints(definition, parents, resolved), resolveLinkFields(definition.getFieldDefinitions(), parents, resolved), resolveComputedFields(definition.getFieldDefinitions(), parents, resolved));
        resolved.put(name, result);
        visiting.remove(name);
        return result;
    }

    private static List<String> ancestors(List<Type> implementsTypes, Map<String, InterfaceDefinition> interfaces) {
        var parents = new java.util.LinkedHashSet<String>();
        for (Type<?> type : implementsTypes) {
            String name = ((TypeName) type).getName();
            var parent = interfaces.get(name);
            if (parent == null) throw new SchemaValidationException(List.of("unknown interface: " + name));
            parents.add(name);
            parents.addAll(parent.interfaces());
        }
        return List.copyOf(parents);
    }

    private static List<PropertyDefinition> resolveFields(List<FieldDefinition> fields, List<String> parents, Map<String, InterfaceDefinition> interfaces) {
        var result = new LinkedHashMap<String, PropertyDefinition>();
        for (String parent : parents) for (var field : interfaces.get(parent).properties()) mergeField(result, field);
        var declared = new java.util.HashSet<String>();
        for (var field : fields) {
            if (!declared.add(field.getName())) throw new SchemaValidationException(List.of("duplicate field: " + field.getName()));
            if (!hasDirective(field, "link") && !hasDirective(field, "computed")) mergeField(result, parseProperty(field));
        }
        return List.copyOf(result.values());
    }

    private static List<LinkFieldDefinition> resolveLinkFields(List<FieldDefinition> fields, List<String> parents,
                                                               Map<String, InterfaceDefinition> interfaces) {
        var result = new LinkedHashMap<String, LinkFieldDefinition>();
        for (String parent : parents) {
            for (var field : interfaces.get(parent).linkFields()) mergeLinkField(result, field);
        }
        for (var field : fields) {
            if (!hasDirective(field, "link")) {
                if (result.containsKey(field.getName())) {
                    throw new SchemaValidationException(List.of("inherited relationship field cannot change kind: " + field.getName()));
                }
                continue;
            }
            if (field.getDirectives("link").size() != 1) {
                throw new SchemaValidationException(List.of("duplicate link directive: " + field.getName()));
            }
            for (var directive : field.getDirectives()) {
                if (!Set.of("link", "sensitive", "readonly").contains(directive.getName())) {
                    throw new SchemaValidationException(List.of("unsupported relationship field directive: " + field.getName() + " @" + directive.getName()));
                }
            }
            var link = requiredDirective(field, "link");
            for (var argument : link.getArguments()) {
                if (!Set.of("type", "direction", "history").contains(argument.getName())) {
                    throw new SchemaValidationException(List.of("unknown link argument: " + argument.getName()));
                }
            }
            boolean history = false;
            var historyArgument = link.getArgument("history");
            if (historyArgument != null) {
                if (!(historyArgument.getValue() instanceof graphql.language.BooleanValue value)) {
                    throw new SchemaValidationException(List.of("link history must be a boolean"));
                }
                history = value.isValue();
            }
            var direction = link.getArgument("direction") == null ? Direction.OUTBOUND
                    : Direction.valueOf(requiredEnumArgument(link, "direction"));
            mergeLinkField(result, new LinkFieldDefinition(field.getName(), typeName(field.getType()),
                    field.getType() instanceof NonNullType, requiredStringArgument(link, "type"), direction,
                    history, hasDirective(field, "sensitive")));
        }
        return List.copyOf(result.values());
    }

    private static List<ComputedFieldDefinition> resolveComputedFields(List<FieldDefinition> fields, List<String> parents,
                                                                       Map<String, InterfaceDefinition> interfaces) {
        var result = new LinkedHashMap<String, ComputedFieldDefinition>();
        for (String parent : parents) {
            for (var field : interfaces.get(parent).computedFields()) mergeComputedField(result, field);
        }
        for (var field : fields) {
            if (!hasDirective(field, "computed")) {
                if (result.containsKey(field.getName())) throw new SchemaValidationException(List.of("inherited computed field cannot change kind: " + field.getName()));
                continue;
            }
            if (field.getDirectives("computed").size() != 1) throw new SchemaValidationException(List.of("duplicate computed directive: " + field.getName()));
            for (var directive : field.getDirectives()) {
                if (!Set.of("computed", "sensitive", "readonly").contains(directive.getName())) {
                    throw new SchemaValidationException(List.of("unsupported computed field directive: " + directive.getName()));
                }
            }
            var directive = requiredDirective(field, "computed");
            for (var argument : directive.getArguments()) {
                if (!Set.of("fn", "args", "cache", "ttl").contains(argument.getName())) throw new SchemaValidationException(List.of("unknown computed argument: " + argument.getName()));
            }
            var arguments = new LinkedHashMap<String, Object>();
            if (directive.getArgument("args") != null) {
                Object raw = literal(directive.getArgument("args").getValue());
                if (!(raw instanceof Map<?, ?> values)) throw new SchemaValidationException(List.of("computed args must be an object"));
                values.forEach((name, value) -> arguments.put((String) name, value));
            }
            var cache = directive.getArgument("cache") == null ? ComputedFieldDefinition.Cache.LAZY
                    : ComputedFieldDefinition.Cache.valueOf(requiredEnumArgument(directive, "cache"));
            String ttl = directive.getArgument("ttl") == null ? null : requiredStringArgument(directive, "ttl");
            mergeComputedField(result, new ComputedFieldDefinition(field.getName(), typeName(field.getType()), field.getType() instanceof NonNullType,
                    requiredStringArgument(directive, "fn"), arguments, cache, ttl, hasDirective(field, "sensitive")));
        }
        return List.copyOf(result.values());
    }

    private static void mergeComputedField(Map<String, ComputedFieldDefinition> fields, ComputedFieldDefinition field) {
        var inherited = fields.putIfAbsent(field.name(), field);
        if (inherited != null && !inherited.equals(field)) throw new SchemaValidationException(List.of("conflicting inherited computed field: " + field.name()));
    }

    private static void mergeLinkField(Map<String, LinkFieldDefinition> fields, LinkFieldDefinition field) {
        var inherited = fields.putIfAbsent(field.name(), field);
        if (inherited != null && !inherited.equals(field)) {
            throw new SchemaValidationException(List.of("conflicting inherited relationship field: " + field.name()));
        }
    }

    private static void mergeField(Map<String, PropertyDefinition> fields, PropertyDefinition field) {
        var inherited = fields.putIfAbsent(field.name(), field);
        if (inherited != null && !inherited.equals(field)) throw new SchemaValidationException(List.of("conflicting inherited property: " + field.name()));
    }

    private static List<String> inheritedConstraints(DirectivesContainer<?> definition, List<String> parents, Map<String, InterfaceDefinition> interfaces) {
        var expressions = new java.util.LinkedHashSet<String>();
        for (String parent : parents) expressions.addAll(interfaces.get(parent).constraints());
        expressions.addAll(constraints(definition));
        return List.copyOf(expressions);
    }

    private static List<String> constraints(DirectivesContainer<?> source) {
        return source.getDirectives().stream().filter(directive -> directive.getName().equals("constraint"))
                .map(directive -> requiredStringArgument(directive, "expr")).toList();
    }

    private static ActionTypeDefinition parseAction(graphql.language.ObjectTypeDefinition definition) {
        Directive directive = requiredDirective(definition, "actionType");
        String permission = directive.getArgument("permission") == null ? null : requiredStringArgument(directive, "permission");
        return new ActionTypeDefinition(definition.getName(), definition.getFieldDefinitions().stream()
                .filter(field -> hasDirective(field, "param"))
                .map(field -> new ActionParameter(field.getName(), typeName(field.getType()),
                        field.getType() instanceof NonNullType))
                .toList(), permission);
    }

    private static PropertyDefinition parseProperty(FieldDefinition field) {
        var defaults = field.getDirectives().stream().filter(directive -> directive.getName().equals("default")).toList();
        if (defaults.size() > 1) throw new SchemaValidationException(List.of("duplicate default: " + field.getName()));
        Object defaultValue = null;
        if (!defaults.isEmpty()) {
            var argument = defaults.getFirst().getArgument("value");
            if (argument == null) throw new SchemaValidationException(List.of("default requires value: " + field.getName()));
            defaultValue = literal(argument.getValue());
        }
        return new PropertyDefinition(
                field.getName(),
                typeName(field.getType()),
                field.getType() instanceof NonNullType,
                hasDirective(field, "primary"),
                hasDirective(field, "unique"),
                hasDirective(field, "indexed"),
                hasDirective(field, "sensitive"),
                hasDirective(field, "immutable"), hasDirective(field, "readonly"), !defaults.isEmpty(), defaultValue, constraints(field));
    }

    private static Object literal(graphql.language.Value<?> value) {
        if (value instanceof StringValue text) return text.getValue();
        if (value instanceof graphql.language.EnumValue enumeration) return enumeration.getName();
        if (value instanceof graphql.language.BooleanValue bool) return bool.isValue();
        if (value instanceof graphql.language.NullValue) return null;
        if (value instanceof graphql.language.IntValue number) {
            var exact = number.getValue();
            if (exact.bitLength() < 32) return exact.intValue();
            if (exact.bitLength() < 64) return exact.longValue();
            return exact;
        }
        if (value instanceof graphql.language.FloatValue number) {
            var exact = number.getValue();
            double approximate = exact.doubleValue();
            return Double.isFinite(approximate) && java.math.BigDecimal.valueOf(approximate).compareTo(exact) == 0 ? approximate : exact;
        }
        if (value instanceof graphql.language.ArrayValue list) return list.getValues().stream().map(OdlParser::literal).toList();
        if (value instanceof graphql.language.ObjectValue object) {
            var fields = new LinkedHashMap<String, Object>();
            for (var field : object.getObjectFields()) {
                if (fields.containsKey(field.getName())) throw new SchemaValidationException(List.of("duplicate default object key: " + field.getName()));
                fields.put(field.getName(), literal(field.getValue()));
            }
            return fields;
        }
        throw new SchemaValidationException(List.of("default must be a literal"));
    }

    private static Namespace findNamespace(Document document) {
        Namespace result = null;
        for (Definition<?> definition : document.getDefinitions()) {
            if (!(definition instanceof SchemaDefinition) && !(definition instanceof SchemaExtensionDefinition)) continue;
            var container = (DirectivesContainer<?>) definition;
            for (var directive : container.getDirectives()) {
                if (!directive.getName().equals("namespace")) continue;
                var declared = new Namespace(requiredStringArgument(directive, "name"), requiredStringArgument(directive, "version"));
                if (result != null && !result.equals(declared)) throw new SchemaValidationException(List.of("conflicting namespace declarations"));
                result = declared;
            }
        }
        if (result == null) throw new SchemaValidationException(List.of("schema must declare @namespace(name, version)"));
        return result;
    }

    private static boolean hasDirective(DirectivesContainer<?> node, String name) {
        return node.getDirectives().stream().anyMatch(directive -> directive.getName().equals(name));
    }

    private static Directive requiredDirective(DirectivesContainer<?> node, String name) {
        return node.getDirectives().stream().filter(directive -> directive.getName().equals(name))
                .findFirst().orElseThrow(() -> new SchemaValidationException(
                        List.of("missing @" + name + " directive on " + node)));
    }

    private static String requiredStringArgument(Directive directive, String name) {
        Argument argument = directive.getArgument(name);
        if (argument == null || !(argument.getValue() instanceof StringValue value)) {
            throw new SchemaValidationException(List.of("@" + directive.getName() + " requires string argument " + name));
        }
        return value.getValue();
    }

    private static String requiredEnumArgument(Directive directive, String name) {
        Argument argument = directive.getArgument(name);
        if (argument == null || !(argument.getValue() instanceof graphql.language.EnumValue value)) {
            throw new SchemaValidationException(List.of("@" + directive.getName() + " requires enum argument " + name));
        }
        return value.getName();
    }

    private static String typeName(Type<?> type) {
        if (type instanceof TypeName named) {
            return named.getName();
        }
        if (type instanceof NonNullType nonNull) {
            return typeName(nonNull.getType());
        }
        Type<?> element = ((graphql.language.ListType) type).getType();
        return "[" + typeName(element) + (element instanceof NonNullType ? "!" : "") + "]";
    }

    private record Namespace(String name, String version) {}
}
