package org.openfoundry.foundation.api;

import graphql.Scalars;
import graphql.schema.*;

final class GraphqlSearchTypes {
    private final GraphQLEnumType mode = GraphQLEnumType.newEnum().name("SearchMode")
            .value("TERMS", "TERMS").value("PHRASE", "PHRASE").build();

    GraphQLFieldDefinition field(GraphQLObjectType object, GraphQLInputObjectType filter, ApplicationService application) {
        var hit = GraphQLObjectType.newObject().name("SearchHit_" + object.getName())
                .field(GraphQLFieldDefinition.newFieldDefinition().name("node").type(GraphQLNonNull.nonNull(object)))
                .field(GraphQLFieldDefinition.newFieldDefinition().name("score").type(GraphQLNonNull.nonNull(Scalars.GraphQLFloat)))
                .field(GraphQLFieldDefinition.newFieldDefinition().name("highlights").type(GraphqlValueScalars.TYPES.get("JSON")))
                .field(GraphQLFieldDefinition.newFieldDefinition().name("cursor").type(GraphQLNonNull.nonNull(Scalars.GraphQLString))).build();
        var result = GraphQLObjectType.newObject().name("SearchResult_" + object.getName())
                .field(GraphQLFieldDefinition.newFieldDefinition().name("hits").type(GraphQLNonNull.nonNull(GraphQLList.list(GraphQLNonNull.nonNull(hit)))))
                .field(GraphQLFieldDefinition.newFieldDefinition().name("totalCount").type(GraphQLNonNull.nonNull(Scalars.GraphQLInt)))
                .field(GraphQLFieldDefinition.newFieldDefinition().name("hasNextPage").type(GraphQLNonNull.nonNull(Scalars.GraphQLBoolean))).build();
        return GraphQLFieldDefinition.newFieldDefinition().name("search" + object.getName() + "s").type(GraphQLNonNull.nonNull(result))
                .argument(GraphQLArgument.newArgument().name("query").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)))
                .argument(GraphQLArgument.newArgument().name("fields").type(GraphQLList.list(GraphQLNonNull.nonNull(Scalars.GraphQLString))))
                .argument(GraphQLArgument.newArgument().name("filter").type(filter))
                .argument(GraphQLArgument.newArgument().name("mode").type(mode))
                .argument(GraphQLArgument.newArgument().name("first").type(Scalars.GraphQLInt))
                .argument(GraphQLArgument.newArgument().name("offset").type(Scalars.GraphQLInt))
                .argument(GraphQLArgument.newArgument().name("after").type(Scalars.GraphQLString))
                .dataFetcher(env -> {
                    ApiRequestContext request = env.getGraphQlContext().get("request");
                    if (request == null) throw new IllegalStateException("GraphQL request context is missing");
                    return application.searchObjects(request.request(), request.principal(), object.getName(), SearchQuery.fromJson(env.getArguments()));
                }).build();
    }

    static String sdl(String name) {
        return "type SearchHit_" + name + " { node: " + name + "! score: Float! highlights: JSON cursor: String! }\n"
                + "type SearchResult_" + name + " { hits: [SearchHit_" + name + "!]! totalCount: Int! hasNextPage: Boolean! }\n";
    }
}
