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

    public static GraphQL create(OntologySchema schema, ApplicationService application) {
        return create(schema, application, Map.of());
    }

    public static GraphQL create(OntologySchema schema, ApplicationService application,
                                 Map<String, ActionManifest> manifests) {
        Map<String, GraphQLObjectType> objectTypes = new java.util.LinkedHashMap<>();
        for (ObjectTypeDefinition definition : schema.objectTypes()) {
            GraphQLObjectType.Builder object = GraphQLObjectType.newObject().name(definition.name());
            object.field(GraphQLFieldDefinition.newFieldDefinition().name("id").type(Scalars.GraphQLID).build());
            for (PropertyDefinition property : definition.properties()) {
                if (property.primary()) continue;
                GraphQLOutputType type = scalar(property.type());
                if (property.required()) type = GraphQLNonNull.nonNull(type);
                object.field(GraphQLFieldDefinition.newFieldDefinition().name(property.name()).type(type).build());
            }
            objectTypes.put(definition.name(), object.build());
        }

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
            query.field(GraphQLFieldDefinition.newFieldDefinition().name(singular + "s")
                    .type(GraphQLNonNull.nonNull(GraphQLList.list(GraphQLNonNull.nonNull(type))))
                    .argument(GraphQLArgument.newArgument().name("first").type(Scalars.GraphQLInt).defaultValue(100))
                    .argument(GraphQLArgument.newArgument().name("offset").type(Scalars.GraphQLInt).defaultValue(0))
                    .dataFetcher(env -> {
                        ApiRequestContext request = request(env);
                        int first = env.getArgumentOrDefault("first", 100);
                        int offset = env.getArgumentOrDefault("offset", 0);
                        return application.listObjects(request.request(), request.principal(), definition.name(),
                                new QueryOptions(first, offset, null, null, false));
                    }).build());
        }

        graphql.schema.GraphQLObjectType.Builder mutation = GraphQLObjectType.newObject().name("Mutation");
        for (Map.Entry<String, ActionManifest> entry : manifests.entrySet()) {
            ActionManifest manifest = entry.getValue();
            mutation.field(GraphQLFieldDefinition.newFieldDefinition().name(lower(entry.getKey()))
                    .type(Scalars.GraphQLString)
                    .argument(GraphQLArgument.newArgument().name("input").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)))
                    .dataFetcher(environment -> {
                        ApiRequestContext request = request(environment);
                        String rawInput = environment.getArgument("input");
                        Map<String, Object> input;
                        try {
                            input = new ObjectMapper().readValue(rawInput, new TypeReference<>() {});
                        } catch (Exception exception) {
                            throw new IllegalArgumentException("Action input must be valid JSON", exception);
                        }
                        String key = environment.getGraphQlContext().get("idempotencyKey");
                        return String.valueOf(application.execute(manifest, request.request(), request.principal(), input, key));
                    }).build());
        }
        GraphQLSchema.Builder graphQLSchema = GraphQLSchema.newSchema().query(query.build());
        if (!manifests.isEmpty()) graphQLSchema.mutation(mutation.build());
        return GraphQL.newGraphQL(graphQLSchema.build()).build();
    }

    private static ApiRequestContext request(DataFetchingEnvironment environment) {
        ApiRequestContext request = environment.getGraphQlContext().get("request");
        if (request == null) throw new IllegalStateException("GraphQL request context is missing");
        return request;
    }

    private static GraphQLOutputType scalar(String type) {
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
