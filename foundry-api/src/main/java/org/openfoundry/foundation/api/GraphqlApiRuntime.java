package org.openfoundry.foundation.api;

import graphql.GraphQL;
import graphql.Scalars;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLOutputType;
import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.LinkRecord;
import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.schema.LinkFieldDefinition;
import org.openfoundry.foundation.spi.QueryOptions;
import org.openfoundry.foundation.spi.schema.ObjectTypeDefinition;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.spi.schema.PropertyDefinition;
import org.openfoundry.foundation.actions.ActionManifest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Runtime GraphQL query facade generated from the ontology schema. */
public final class GraphqlApiRuntime {
    private GraphqlApiRuntime() {}

    public enum ActionMode { TYPED, LEGACY_JSON }

    public static GraphQL create(OntologySchema schema, ApplicationService application) {
        return create(schema, application, Map.of());
    }

    public static GraphQL create(OntologySchema schema, ApplicationService application,
                                 Map<String, ActionManifest> manifests) {
        return create(schema, application, manifests, ActionMode.TYPED);
    }

    public static GraphQL createLegacy(OntologySchema schema, ApplicationService application, Map<String, ActionManifest> manifests) {
        return create(schema, application, manifests, ActionMode.LEGACY_JSON);
    }

    public static GraphQL create(OntologySchema schema, ApplicationService application,
                                 Map<String, ActionManifest> manifests, ActionMode actionMode) {
        var declared = schema.actionTypes().stream().collect(java.util.stream.Collectors.toMap(type -> type.name(), type -> type));
        var active = manifests.keySet().stream().sorted().map(name -> {
            var type = declared.get(name);
            if (type == null || !name.equals(manifests.get(name).action())) throw new IllegalArgumentException("Unregistered GraphQL action: " + name);
            return type;
        }).toList();
        GraphqlActionTypes.validateNames(schema, actionMode == ActionMode.TYPED ? active : List.of());
        Map<String, graphql.schema.GraphQLEnumType> enums = new java.util.LinkedHashMap<>();
        schema.enums().forEach((name, values) -> {
            var enumeration = graphql.schema.GraphQLEnumType.newEnum().name(name);
            values.forEach(value -> enumeration.value(value, value));
            enums.put(name, enumeration.build());
        });
        Map<String, graphql.schema.GraphQLInterfaceType> interfaces = new java.util.LinkedHashMap<>();
        for (var definition : schema.interfaces()) {
            var iface = graphql.schema.GraphQLInterfaceType.newInterface().name(definition.name())
                    .typeResolver(environment -> environment.getSchema().getObjectType(entityType(environment.getObject())));
            definition.interfaces().forEach(name -> iface.withInterface(graphql.schema.GraphQLTypeReference.typeRef(name)));
            definition.properties().forEach(property -> iface.field(field(property, enums)));
            definition.linkFields().forEach(property -> iface.field(linkField(property, application)));
            definition.computedFields().forEach(property -> iface.field(computedField(property)));
            interfaces.put(definition.name(), iface.build());
        }
        Map<String, GraphQLObjectType> objectTypes = new java.util.LinkedHashMap<>();
        for (ObjectTypeDefinition definition : schema.objectTypes()) {
            GraphQLObjectType.Builder object = GraphQLObjectType.newObject().name(definition.name());
            definition.interfaces().forEach(name -> object.withInterface(interfaces.get(name)));
            definition.properties().forEach(property -> object.field(field(property, enums)));
            definition.linkFields().forEach(property -> object.field(linkField(property, application)));
            definition.computedFields().forEach(property -> object.field(computedField(property)));
            objectTypes.put(definition.name(), object.build());
        }

        Map<String, GraphQLObjectType> linkTypes = new java.util.LinkedHashMap<>();
        for (var definition : schema.linkTypes()) {
            var link = GraphQLObjectType.newObject().name(definition.name());
            definition.interfaces().forEach(name -> link.withInterface(interfaces.get(name)));
            definition.properties().forEach(property -> link.field(field(property, enums)));
            definition.linkFields().forEach(property -> link.field(linkField(property, application)));
            linkTypes.put(definition.name(), link.build());
        }

        var queryInputs = GraphqlQueryTypes.inputs(schema, enums);
        var pageInfo = GraphqlQueryTypes.pageInfo();
        GraphQLObjectType.Builder query = GraphQLObjectType.newObject().name("Query");
        for (ObjectTypeDefinition definition : schema.objectTypes()) {
            GraphQLObjectType type = objectTypes.get(definition.name());
            String singular = lower(definition.name());
            query.field(GraphQLFieldDefinition.newFieldDefinition().name(singular)
                    .type(type).argument(GraphQLArgument.newArgument().name("id").type(GraphQLNonNull.nonNull(Scalars.GraphQLID)))
                    .dataFetcher(env -> {
                        ApiRequestContext request = request(env);
                        String id = env.getArgument("id");
                        return application.getObject(request.request(), request.principal(), definition.name(), id);
                    }).build());
            query.field(GraphqlQueryTypes.arguments(GraphQLFieldDefinition.newFieldDefinition().name(singular + "s")
                    .type(GraphQLNonNull.nonNull(GraphQLList.list(GraphQLNonNull.nonNull(type)))), definition.name(), queryInputs, false)
                    .dataFetcher(env -> {
                        var request = request(env);
                        var input = GraphqlQueryTypes.query(env);
                        if (input.filter().isEmpty() && input.orderBy().isEmpty()) {
                            return application.listObjects(request.request(), request.principal(), definition.name(), input.options());
                        }
                        return application.queryObjects(request.request(), request.principal(), definition.name(), input).items();
                    }).build());
            query.field(GraphqlQueryTypes.arguments(GraphQLFieldDefinition.newFieldDefinition().name(singular + "sConnection")
                    .type(GraphQLNonNull.nonNull(GraphqlQueryTypes.connection(type, pageInfo))), definition.name(), queryInputs, true)
                    .dataFetcher(env -> {
                        var request = request(env);
                        return application.queryObjects(request.request(), request.principal(), definition.name(), GraphqlQueryTypes.query(env)).connection();
                    }).build());
        }

        graphql.schema.GraphQLObjectType.Builder mutation = GraphQLObjectType.newObject().name("Mutation");
        var errors = GraphqlActionTypes.errors();
        var affected = GraphqlActionTypes.affected();
        for (var definition : active) {
            var manifest = manifests.get(definition.name());
            var field = GraphQLFieldDefinition.newFieldDefinition().name(lower(definition.name()));
            if (actionMode == ActionMode.LEGACY_JSON) {
                field.type(Scalars.GraphQLString).argument(GraphQLArgument.newArgument().name("input").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)));
            } else {
                field.type(GraphQLNonNull.nonNull(GraphqlActionTypes.result(definition, errors, affected)));
                if (!definition.parameters().isEmpty()) {
                    field.argument(GraphQLArgument.newArgument().name("input").type(GraphQLNonNull.nonNull(GraphqlActionTypes.input(definition, schema, enums))));
                }
            }
            mutation.field(field.dataFetcher(environment -> {
                ApiRequestContext request = request(environment);
                Map<String, Object> input;
                if (actionMode == ActionMode.LEGACY_JSON) {
                    try { input = new ObjectMapper().readValue((String) environment.getArgument("input"), new TypeReference<>() {}); }
                    catch (Exception invalid) { throw new IllegalArgumentException("Action input must be valid JSON", invalid); }
                } else {
                    input = environment.getArgumentOrDefault("input", Map.of());
                }
                String key = environment.getGraphQlContext().get("idempotencyKey");
                var result = application.execute(manifest, request.request(), request.principal(), input, key);
                return actionMode == ActionMode.LEGACY_JSON ? new ObjectMapper().writeValueAsString(GraphqlActionTypes.legacyResult(result)) : result;
            }).build());
        }
        GraphQLSchema.Builder graphQLSchema = GraphQLSchema.newSchema().query(query.build());
        if (!manifests.isEmpty()) graphQLSchema.mutation(mutation.build());
        graphQLSchema.additionalTypes(new java.util.HashSet<>(interfaces.values()));
        graphQLSchema.additionalTypes(new java.util.HashSet<>(enums.values()));
        graphQLSchema.additionalTypes(new java.util.HashSet<>(linkTypes.values()));
        return GraphQL.newGraphQL(graphQLSchema.build()).build();
    }

    private static ApiRequestContext request(DataFetchingEnvironment environment) {
        ApiRequestContext request = environment.getGraphQlContext().get("request");
        if (request == null) throw new IllegalStateException("GraphQL request context is missing");
        return request;
    }

    private static GraphQLFieldDefinition field(PropertyDefinition property, Map<String, graphql.schema.GraphQLEnumType> enums) {
        GraphQLOutputType type = scalar(property.type(), enums);
        if (property.primary()) type = GraphQLNonNull.nonNull(type);
        return GraphQLFieldDefinition.newFieldDefinition().name(property.name()).type(type).dataFetcher(environment -> {
            Object source = environment.getSource();
            if (source instanceof ObjectRecord record) {
                return property.primary() ? record.id() : record.properties().get(property.name());
            }
            if (source instanceof LinkRecord record) {
                return property.primary() ? record.id() : record.properties().get(property.name());
            }
            throw new IllegalStateException("Unsupported GraphQL entity source");
        }).build();
    }

    private static GraphQLFieldDefinition computedField(org.openfoundry.foundation.spi.schema.ComputedFieldDefinition field) {
        return GraphQLFieldDefinition.newFieldDefinition().name(field.name()).type(Scalars.GraphQLInt)
                .dataFetcher(environment -> ((ObjectRecord) environment.getSource()).properties().get(field.name())).build();
    }

    private static String entityType(Object source) {
        if (source instanceof ObjectRecord object) return object.type();
        if (source instanceof LinkRecord link) return link.type();
        throw new IllegalStateException("Unsupported GraphQL entity source");
    }

    private static GraphQLFieldDefinition linkField(LinkFieldDefinition field, ApplicationService application) {
        GraphQLOutputType type = graphql.schema.GraphQLTypeReference.typeRef(field.targetType());
        if (field.many()) {
            if (field.type().endsWith("!]")) type = GraphQLNonNull.nonNull(type);
            type = GraphQLList.list(type);
        }
        var builder = GraphQLFieldDefinition.newFieldDefinition().name(field.name()).type(type);
        if (field.many()) {
            builder.argument(GraphQLArgument.newArgument().name("first").type(Scalars.GraphQLInt).defaultValue(100));
            builder.argument(GraphQLArgument.newArgument().name("offset").type(Scalars.GraphQLInt).defaultValue(0));
        }
        return builder.dataFetcher(environment -> {
            var request = request(environment);
            ObjectRecord source = environment.getSource();
            return application.readLinkField(request.request(), request.principal(), source.key(), field.name(),
                    new QueryOptions(environment.getArgumentOrDefault("first", 100),
                            environment.getArgumentOrDefault("offset", 0), null, null, false));
        }).build();
    }

    private static GraphQLOutputType scalar(String type, Map<String, graphql.schema.GraphQLEnumType> enums) {
        if (type.endsWith("!")) return GraphQLNonNull.nonNull(scalar(type.substring(0, type.length() - 1), enums));
        if (type.startsWith("[") && type.endsWith("]")) return GraphQLList.list(scalar(type.substring(1, type.length() - 1), enums));
        if (enums.containsKey(type)) return enums.get(type);
        if (GraphqlValueScalars.TYPES.containsKey(type)) return GraphqlValueScalars.TYPES.get(type);
        return switch (type) {
            case "ID" -> Scalars.GraphQLID;
            case "Int" -> Scalars.GraphQLInt;
            case "Float" -> Scalars.GraphQLFloat;
            case "Boolean" -> Scalars.GraphQLBoolean;
            case "Date", "DateTime", "JSON" -> Scalars.GraphQLString;
            default -> Scalars.GraphQLString;
        };
    }

    private static String lower(String value) {
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }
}
