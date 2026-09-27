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

/** Parses the ODL subset used by Foundation v0.1. ODL is GraphQL SDL plus directives. */
public final class OdlParser {
    private final Parser parser = new Parser();

    public OntologySchema parse(String source) {
        Objects.requireNonNull(source, "source must not be null");
        Document document;
        try {
            document = parser.parseDocument(source);
        } catch (RuntimeException exception) {
            throw new SchemaValidationException(List.of("ODL parse error: " + exception.getMessage()));
        }

        Namespace namespace = findNamespace(document);
        List<ObjectTypeDefinition> objects = new ArrayList<>();
        List<LinkTypeDefinition> links = new ArrayList<>();
        List<ActionTypeDefinition> actions = new ArrayList<>();

        for (Definition<?> definition : document.getDefinitions()) {
            if (!(definition instanceof graphql.language.ObjectTypeDefinition object)) {
                continue;
            }
            if (hasDirective(object, "linkType")) {
                links.add(parseLink(object));
            } else if (hasDirective(object, "actionType")) {
                actions.add(parseAction(object));
            } else if (hasDirective(object, "objectType")) {
                objects.add(parseObject(object));
            }
        }

        return new OntologySchema(namespace.name(), namespace.version(), objects, links, actions);
    }

    private static org.openfoundry.foundation.spi.schema.ObjectTypeDefinition parseObject(
            graphql.language.ObjectTypeDefinition definition) {
        return new org.openfoundry.foundation.spi.schema.ObjectTypeDefinition(definition.getName(), definition.getFieldDefinitions().stream()
                .filter(field -> !hasDirective(field, "link") && !hasDirective(field, "computed"))
                .map(OdlParser::parseProperty)
                .toList());
    }

    private static LinkTypeDefinition parseLink(graphql.language.ObjectTypeDefinition definition) {
        Directive directive = requiredDirective(definition, "linkType");
        return new LinkTypeDefinition(
                definition.getName(),
                requiredStringArgument(directive, "from"),
                requiredStringArgument(directive, "to"),
                Cardinality.valueOf(requiredEnumArgument(directive, "cardinality")),
                definition.getFieldDefinitions().stream().map(OdlParser::parseProperty).toList());
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
        return new PropertyDefinition(
                field.getName(),
                typeName(field.getType()),
                field.getType() instanceof NonNullType,
                hasDirective(field, "primary"),
                hasDirective(field, "unique"),
                hasDirective(field, "indexed"),
                hasDirective(field, "sensitive"),
                hasDirective(field, "immutable"));
    }

    private static Namespace findNamespace(Document document) {
        for (Definition<?> definition : document.getDefinitions()) {
            if (!(definition instanceof SchemaDefinition) && !(definition instanceof SchemaExtensionDefinition)) {
                continue;
            }
            DirectivesContainer<?> container = (DirectivesContainer<?>) definition;
            Directive namespace = container.getDirectives().stream()
                    .filter(candidate -> candidate.getName().equals("namespace"))
                    .findFirst().orElse(null);
            if (namespace != null) {
                return new Namespace(requiredStringArgument(namespace, "name"),
                        requiredStringArgument(namespace, "version"));
            }
        }
        throw new SchemaValidationException(List.of("schema must declare @namespace(name, version)"));
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
        return "[" + typeName(((graphql.language.ListType) type).getType()) + "]";
    }

    private record Namespace(String name, String version) {}
}
