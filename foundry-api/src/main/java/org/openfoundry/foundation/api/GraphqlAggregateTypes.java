package org.openfoundry.foundation.api;

import graphql.Scalars;
import graphql.schema.*;

import java.util.List;

/** Upstream aggregate input and result shape, with optional group ordering and pagination. */
final class GraphqlAggregateTypes {
    static final List<String> NAMES = List.of("AggregateFunction", "AggregateFieldInput", "AggregateOrderInput", "AggregateGroup", "AggregateResult");
    private final GraphQLInputObjectType fieldInput;
    private final GraphQLInputObjectType orderInput;
    private final GraphQLObjectType result;

    GraphqlAggregateTypes() {
        var function = GraphQLEnumType.newEnum().name("AggregateFunction");
        for (var value : AggregateQuery.Function.values()) function.value(value.name(), value.name());
        fieldInput = GraphQLInputObjectType.newInputObject().name("AggregateFieldInput")
                .field(GraphQLInputObjectField.newInputObjectField().name("field").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)))
                .field(GraphQLInputObjectField.newInputObjectField().name("fn").type(GraphQLNonNull.nonNull(function.build())))
                .field(GraphQLInputObjectField.newInputObjectField().name("alias").type(Scalars.GraphQLString)).build();
        orderInput = GraphQLInputObjectType.newInputObject().name("AggregateOrderInput")
                .field(GraphQLInputObjectField.newInputObjectField().name("field").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)))
                .field(GraphQLInputObjectField.newInputObjectField().name("direction").type(GraphQLNonNull.nonNull(GraphQLTypeReference.typeRef("SortDirection")))).build();
        var group = GraphQLObjectType.newObject().name("AggregateGroup")
                .field(GraphQLFieldDefinition.newFieldDefinition().name("keys").type(GraphQLNonNull.nonNull(GraphqlValueScalars.TYPES.get("JSON"))))
                .field(GraphQLFieldDefinition.newFieldDefinition().name("values").type(GraphQLNonNull.nonNull(GraphqlValueScalars.TYPES.get("JSON")))).build();
        result = GraphQLObjectType.newObject().name("AggregateResult")
                .field(GraphQLFieldDefinition.newFieldDefinition().name("groups").type(GraphQLNonNull.nonNull(GraphQLList.list(GraphQLNonNull.nonNull(group)))))
                .field(GraphQLFieldDefinition.newFieldDefinition().name("totalGroups").type(GraphQLNonNull.nonNull(Scalars.GraphQLInt))).build();
    }

    GraphQLFieldDefinition field(String type, String name, GraphQLInputObjectType filter, ApplicationService application) {
        return GraphQLFieldDefinition.newFieldDefinition().name(name).type(GraphQLNonNull.nonNull(result))
                .argument(GraphQLArgument.newArgument().name("filter").type(filter))
                .argument(GraphQLArgument.newArgument().name("groupBy").type(GraphQLList.list(GraphQLNonNull.nonNull(Scalars.GraphQLString))))
                .argument(GraphQLArgument.newArgument().name("fields").type(GraphQLNonNull.nonNull(GraphQLList.list(GraphQLNonNull.nonNull(fieldInput)))))
                .argument(GraphQLArgument.newArgument().name("orderBy").type(GraphQLList.list(GraphQLNonNull.nonNull(orderInput))))
                .argument(GraphQLArgument.newArgument().name("limit").type(Scalars.GraphQLInt))
                .argument(GraphQLArgument.newArgument().name("offset").type(Scalars.GraphQLInt))
                .dataFetcher(env -> {
                    ApiRequestContext request = env.getGraphQlContext().get("request");
                    if (request == null) throw new IllegalStateException("GraphQL request context is missing");
                    return application.aggregateObjects(request.request(), request.principal(), type, AggregateQuery.fromJson(env.getArguments()));
                }).build();
    }

    static String sdl() {
        return """
                enum AggregateFunction { COUNT SUM AVG MIN MAX }
                input AggregateFieldInput { field: String! fn: AggregateFunction! alias: String }
                input AggregateOrderInput { field: String! direction: SortDirection! }
                type AggregateGroup { keys: JSON! values: JSON! }
                type AggregateResult { groups: [AggregateGroup!]! totalGroups: Int! }
                """;
    }
}
