package org.openfoundry.foundation.api;

import graphql.*;
import graphql.schema.*;
import java.util.*;

final class GraphqlConsentTypes {
    static final Set<String> NAMES = Set.of("ConsentInput", "ConsentResult", "ConsentRevocationInput", "ConsentOptOutInput");
    static final Set<String> MUTATIONS = Set.of("recordConsent", "revokeConsent", "setConsentOptOut");
    static final Set<String> QUERIES = Set.of("consentRecords", "consentAudit");
    private final ConsentApi api;
    GraphqlConsentTypes(ApplicationService application) { api = new ConsentApi(application); }

    void install(GraphQLObjectType.Builder query, GraphQLObjectType.Builder mutation) {
        var json = GraphqlValueScalars.TYPES.get("JSON");
        var result = GraphQLObjectType.newObject().name("ConsentResult");
        for (String field : List.of("subject", "subjectType", "purpose", "decision", "sequence", "recordedBy")) {
            result.field(GraphQLFieldDefinition.newFieldDefinition().name(field).type(GraphQLNonNull.nonNull(Scalars.GraphQLString)));
        }
        result.field(GraphQLFieldDefinition.newFieldDefinition().name("recorded").type(GraphQLNonNull.nonNull(Scalars.GraphQLBoolean)));
        result.field(GraphQLFieldDefinition.newFieldDefinition().name("recordedAt").type(GraphQLNonNull.nonNull(GraphqlValueScalars.TYPES.get("DateTime"))));
        result.field(GraphQLFieldDefinition.newFieldDefinition().name("evidence").type(Scalars.GraphQLString));
        result.field(GraphQLFieldDefinition.newFieldDefinition().name("liveInvalidationSupported").type(Scalars.GraphQLBoolean));
        var resultType = result.build();
        for (String operation : List.of("recordConsent", "revokeConsent", "setConsentOptOut")) {
            String inputName = operation.equals("recordConsent") ? "ConsentInput" : operation.equals("revokeConsent") ? "ConsentRevocationInput" : "ConsentOptOutInput";
            var input = GraphQLInputObjectType.newInputObject().name(inputName)
                    .field(GraphQLInputObjectField.newInputObjectField().name("subject").type(GraphQLNonNull.nonNull(Scalars.GraphQLID)))
                    .field(GraphQLInputObjectField.newInputObjectField().name("subjectType").type(Scalars.GraphQLString));
            if (!operation.equals("setConsentOptOut")) input.field(GraphQLInputObjectField.newInputObjectField().name("purpose").type(Scalars.GraphQLString));
            if (operation.equals("recordConsent")) {
                input.field(GraphQLInputObjectField.newInputObjectField().name("decision").type(Scalars.GraphQLString));
                input.field(GraphQLInputObjectField.newInputObjectField().name("evidence").type(Scalars.GraphQLString));
            } else input.field(GraphQLInputObjectField.newInputObjectField().name("reason").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)));
            if (operation.equals("setConsentOptOut")) input.field(GraphQLInputObjectField.newInputObjectField().name("optedOut").type(GraphQLNonNull.nonNull(Scalars.GraphQLBoolean)));
            mutation.field(GraphQLFieldDefinition.newFieldDefinition().name(operation).type(GraphQLNonNull.nonNull(operation.equals("setConsentOptOut") ? json : resultType))
                    .argument(GraphQLArgument.newArgument().name("input").type(GraphQLNonNull.nonNull(input.build())))
                    .dataFetcher(env -> invoke(env, context -> switch (operation) {
                        case "recordConsent" -> api.record(context, env.getArgument("input"));
                        case "revokeConsent" -> api.revoke(context, env.getArgument("input"));
                        default -> api.optOut(context, env.getArgument("input"));
                    })));
        }
        for (String operation : QUERIES) query.field(GraphQLFieldDefinition.newFieldDefinition().name(operation).type(GraphQLNonNull.nonNull(json))
                .argument(GraphQLArgument.newArgument().name("subject").type(GraphQLNonNull.nonNull(Scalars.GraphQLID)))
                .argument(GraphQLArgument.newArgument().name("subjectType").type(Scalars.GraphQLString))
                .dataFetcher(env -> invoke(env, context -> api.records(context, env.getArguments(), operation.equals("consentAudit")))));
    }
    private Object invoke(DataFetchingEnvironment environment, java.util.function.Function<ApiRequestContext, Object> work) {
        ApiRequestContext context = environment.getGraphQlContext().get("request");
        if (context == null) throw new IllegalStateException("GraphQL request context is missing");
        try { return work.apply(context); }
        catch (ConsentApi.NotConfigured missing) { throw error("CONSENT_NOT_CONFIGURED", "Consent service is not configured"); }
        catch (SecurityException denied) { throw error("FORBIDDEN", "Consent administration is not permitted"); }
        catch (IllegalArgumentException invalid) { throw error("VALIDATION_ERROR", "Invalid consent request"); }
    }
    private GraphqlErrorException error(String code, String message) {
        return GraphqlErrorException.newErrorException().message(message).extensions(Map.of("code", code, "retryable", false)).build();
    }
    static String sdl() {
        return """
                input ConsentInput { subject: ID! subjectType: String purpose: String decision: String evidence: String }
                input ConsentRevocationInput { subject: ID! subjectType: String purpose: String reason: String! }
                input ConsentOptOutInput { subject: ID! subjectType: String optedOut: Boolean! reason: String! }
                type ConsentResult { subject: String! subjectType: String! purpose: String! decision: String! recorded: Boolean! sequence: String! recordedAt: DateTime! recordedBy: String! evidence: String liveInvalidationSupported: Boolean }
                """;
    }
    static String queriesSdl() { return "  consentRecords(subject: ID!, subjectType: String): JSON!\n  consentAudit(subject: ID!, subjectType: String): JSON!\n"; }
    static String mutationsSdl() {
        return "  recordConsent(input: ConsentInput!): ConsentResult!\n  revokeConsent(input: ConsentRevocationInput!): ConsentResult!\n  setConsentOptOut(input: ConsentOptOutInput!): JSON!\n";
    }
}
