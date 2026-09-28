package org.openfoundry.foundation.api;

import graphql.*;
import graphql.schema.*;
import org.openfoundry.foundation.spi.*;

import java.util.*;

final class GraphqlObjectSetTypes {
    static final Set<String> NAMES = Set.of("ObjectSet", "CreateObjectSetInput", "UpdateObjectSetInput", "ObjectSetPage", "ObjectSetEdge");
    static final Set<String> QUERIES = Set.of("objectSet", "objectSets", "objectSetByName", "executeObjectSet", "aggregateObjectSet");
    static final Set<String> MUTATIONS = Set.of("createObjectSet", "updateObjectSet", "deleteObjectSet");
    private final ObjectSetService service;

    GraphqlObjectSetTypes(ObjectSetService service) { this.service = service; }

    void install(GraphQLObjectType.Builder query, GraphQLObjectType.Builder mutation, GraphQLObjectType pageInfo, GraphQLObjectType aggregates) {
        var json = GraphqlValueScalars.TYPES.get("JSON");
        var definition = GraphQLObjectType.newObject().name("ObjectSet");
        for (String name : List.of("id", "name", "objectType", "createdBy")) definition.field(output(name, GraphQLNonNull.nonNull(name.equals("id") ? Scalars.GraphQLID : Scalars.GraphQLString)));
        definition.field(output("description", Scalars.GraphQLString));
        definition.field(output("isPublic", GraphQLNonNull.nonNull(Scalars.GraphQLBoolean)));
        definition.field(output("limit", Scalars.GraphQLInt));
        definition.field(GraphQLFieldDefinition.newFieldDefinition().name("version").type(GraphQLNonNull.nonNull(Scalars.GraphQLString))
                .dataFetcher(env -> String.valueOf(((Map<?, ?>) env.getSource()).get("version"))));
        for (String name : List.of("createdAt", "updatedAt")) definition.field(output(name, GraphQLNonNull.nonNull(GraphqlValueScalars.TYPES.get("DateTime"))));
        for (String name : List.of("filter", "orderBy", "aggregation")) definition.field(output(name, json));
        var type = definition.build();
        var create = input("CreateObjectSetInput", true);
        var update = input("UpdateObjectSetInput", false);
        var edge = GraphQLObjectType.newObject().name("ObjectSetEdge")
                .field(GraphQLFieldDefinition.newFieldDefinition().name("node").type(GraphQLNonNull.nonNull(json))
                        .dataFetcher(env -> node(((ObjectQueryResult.Edge) env.getSource()).node())))
                .field(output("cursor", GraphQLNonNull.nonNull(Scalars.GraphQLString))).build();
        var page = GraphQLObjectType.newObject().name("ObjectSetPage")
                .field(output("edges", GraphQLNonNull.nonNull(GraphQLList.list(GraphQLNonNull.nonNull(edge)))))
                .field(output("pageInfo", GraphQLNonNull.nonNull(pageInfo))).field(output("totalCount", GraphQLNonNull.nonNull(Scalars.GraphQLInt))).build();
        query.field(field("objectSet", type).argument(arg("id", GraphQLNonNull.nonNull(Scalars.GraphQLID))).dataFetcher(env -> run(env, ctx -> {
            var result = configured().get(ctx.request(), ctx.principal(), env.getArgument("id"));
            return result == null ? null : result.toMap();
        })));
        query.field(field("objectSetByName", type).argument(arg("name", GraphQLNonNull.nonNull(Scalars.GraphQLString))).dataFetcher(env -> run(env, ctx -> {
            var result = configured().getByName(ctx.request(), ctx.principal(), env.getArgument("name"));
            return result == null ? null : result.toMap();
        })));
        query.field(field("objectSets", GraphQLNonNull.nonNull(GraphQLList.list(GraphQLNonNull.nonNull(type))))
                .argument(arg("objectType", Scalars.GraphQLString)).dataFetcher(env -> run(env, ctx -> configured().list(ctx.request(), ctx.principal(), env.getArgument("objectType")).stream().map(ObjectSetDefinition::toMap).toList())));
        query.field(field("executeObjectSet", GraphQLNonNull.nonNull(page)).argument(arg("id", GraphQLNonNull.nonNull(Scalars.GraphQLID)))
                .argument(arg("first", Scalars.GraphQLInt)).argument(arg("offset", Scalars.GraphQLInt))
                .dataFetcher(env -> run(env, ctx -> configured().execute(ctx.request(), ctx.principal(), env.getArgument("id"), env.getArgument("first"), offset(env)))));
        query.field(field("aggregateObjectSet", GraphQLNonNull.nonNull(aggregates)).argument(arg("id", GraphQLNonNull.nonNull(Scalars.GraphQLID)))
                .dataFetcher(env -> run(env, ctx -> configured().aggregate(ctx.request(), ctx.principal(), env.getArgument("id")))));
        mutation.field(field("createObjectSet", GraphQLNonNull.nonNull(type)).argument(arg("input", GraphQLNonNull.nonNull(create)))
                .dataFetcher(env -> run(env, ctx -> configured().create(ctx.request(), ctx.principal(), env.getArgument("input")).toMap())));
        mutation.field(field("updateObjectSet", GraphQLNonNull.nonNull(type)).argument(arg("id", GraphQLNonNull.nonNull(Scalars.GraphQLID)))
                .argument(arg("input", GraphQLNonNull.nonNull(update))).argument(arg("expectedVersion", Scalars.GraphQLString))
                .dataFetcher(env -> run(env, ctx -> configured().update(ctx.request(), ctx.principal(), env.getArgument("id"), env.getArgument("input"),
                        ObjectSetService.expectedVersion(env.getArgument("expectedVersion"))).toMap())));
        mutation.field(field("deleteObjectSet", GraphQLNonNull.nonNull(Scalars.GraphQLBoolean)).argument(arg("id", GraphQLNonNull.nonNull(Scalars.GraphQLID)))
                .argument(arg("expectedVersion", Scalars.GraphQLString)).dataFetcher(env -> run(env, ctx -> {
                    configured().delete(ctx.request(), ctx.principal(), env.getArgument("id"), ObjectSetService.expectedVersion(env.getArgument("expectedVersion")));
                    return true;
                })));
    }

    private ObjectSetService configured() {
        if (service == null) throw error("OBJECT_SETS_NOT_CONFIGURED", "ObjectSet store is not configured");
        return service;
    }
    private Object run(DataFetchingEnvironment environment, java.util.function.Function<ApiRequestContext, Object> operation) {
        ApiRequestContext context = environment.getGraphQlContext().get("request");
        if (context == null) throw new IllegalStateException("GraphQL request context is missing");
        try { return operation.apply(context); }
        catch (ObjectSetNotFoundException missing) { throw error("OBJECT_SET_NOT_FOUND", missing.getMessage()); }
        catch (ObjectSetConflictException changed) { throw error("OBJECT_SET_CONFLICT", changed.getMessage()); }
        catch (SecurityException denied) { throw error("FORBIDDEN", "ObjectSet access denied"); }
    }
    private static GraphqlErrorException error(String code, String message) {
        return GraphqlErrorException.newErrorException().message(message).extensions(Map.of("code", code, "retryable", false)).build();
    }
    private static int offset(DataFetchingEnvironment env) { Integer offset = env.getArgument("offset"); return offset == null ? 0 : offset; }
    private static GraphQLFieldDefinition.Builder field(String name, GraphQLOutputType type) { return GraphQLFieldDefinition.newFieldDefinition().name(name).type(type); }
    private static GraphQLFieldDefinition output(String name, GraphQLOutputType type) { return field(name, type).build(); }
    private static GraphQLArgument.Builder arg(String name, GraphQLInputType type) { return GraphQLArgument.newArgument().name(name).type(type); }
    private static GraphQLInputObjectType input(String name, boolean create) {
        var builder = GraphQLInputObjectType.newInputObject().name(name);
        builder.field(GraphQLInputObjectField.newInputObjectField().name("name").type(create ? GraphQLNonNull.nonNull(Scalars.GraphQLString) : Scalars.GraphQLString));
        builder.field(GraphQLInputObjectField.newInputObjectField().name("description").type(Scalars.GraphQLString));
        if (create) builder.field(GraphQLInputObjectField.newInputObjectField().name("objectType").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)));
        for (String field : List.of("filter", "orderBy", "aggregation")) builder.field(GraphQLInputObjectField.newInputObjectField().name(field).type(GraphqlValueScalars.TYPES.get("JSON")));
        builder.field(GraphQLInputObjectField.newInputObjectField().name("limit").type(Scalars.GraphQLInt));
        builder.field(GraphQLInputObjectField.newInputObjectField().name("isPublic").type(Scalars.GraphQLBoolean));
        return builder.build();
    }
    private static Map<String, Object> node(ObjectRecord object) {
        return Map.of("id", object.id(), "type", object.type(), "version", object.version(), "properties", object.properties());
    }

    static String sdl() {
        return """
                type ObjectSet { id: ID! name: String! description: String objectType: String! filter: JSON orderBy: JSON limit: Int aggregation: JSON isPublic: Boolean! createdBy: String! createdAt: DateTime! updatedAt: DateTime! version: String! }
                input CreateObjectSetInput { name: String! description: String objectType: String! filter: JSON orderBy: JSON limit: Int aggregation: JSON isPublic: Boolean }
                input UpdateObjectSetInput { name: String description: String filter: JSON orderBy: JSON limit: Int aggregation: JSON isPublic: Boolean }
                type ObjectSetEdge { node: JSON! cursor: String! }
                type ObjectSetPage { edges: [ObjectSetEdge!]! pageInfo: PageInfo! totalCount: Int! }
                """;
    }
    static String queriesSdl() {
        return """
                  objectSet(id: ID!): ObjectSet
                  objectSetByName(name: String!): ObjectSet
                  objectSets(objectType: String): [ObjectSet!]!
                  executeObjectSet(id: ID!, first: Int, offset: Int): ObjectSetPage!
                  aggregateObjectSet(id: ID!): AggregateResult!
                """;
    }
    static String mutationsSdl() {
        return """
                  createObjectSet(input: CreateObjectSetInput!): ObjectSet!
                  updateObjectSet(id: ID!, input: UpdateObjectSetInput!, expectedVersion: String): ObjectSet!
                  deleteObjectSet(id: ID!, expectedVersion: String): Boolean!
                """;
    }
}
