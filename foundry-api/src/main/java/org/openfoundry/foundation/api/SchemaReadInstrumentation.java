package org.openfoundry.foundation.api;

import graphql.*;
import graphql.execution.instrumentation.*;
import graphql.execution.instrumentation.parameters.*;
import graphql.language.OperationDefinition;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLNamedType;
import org.openfoundry.foundation.spi.SchemaVersionMismatchException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** A multi-root query cannot return partial old-model data after a schema cutover. */
final class SchemaReadInstrumentation extends SimplePerformantInstrumentation {
    private final ApplicationService application;

    SchemaReadInstrumentation(ApplicationService application) { this.application = application; }

    private static final class State implements InstrumentationState {
        boolean mutation;
        volatile boolean applicationRead;
    }

    @Override
    public CompletableFuture<InstrumentationState> createStateAsync(InstrumentationCreateStateParameters parameters) {
        return CompletableFuture.completedFuture(new State());
    }

    @Override
    public InstrumentationContext<ExecutionResult> beginExecuteOperation(InstrumentationExecuteOperationParameters parameters, InstrumentationState state) {
        ((State) state).mutation = parameters.getExecutionContext().getOperationDefinition().getOperation() == OperationDefinition.Operation.MUTATION;
        return null;
    }

    @Override
    public DataFetcher<?> instrumentDataFetcher(DataFetcher<?> fetcher, InstrumentationFieldFetchParameters parameters, InstrumentationState state) {
        String parent = ((GraphQLNamedType) parameters.getEnvironment().getParentType()).getName();
        String name = parameters.getEnvironment().getFieldDefinition().getName();
        if (!parent.equals("Query") && !parent.equals("Mutation")) return fetcher;
        return environment -> {
            if (parent.equals("Query") && (!name.startsWith("__") || environment.getGraphQlContext().get("request") != null)) {
                ((State) state).applicationRead = true;
            }
            try { return fetcher.get(environment); }
            catch (SchemaVersionMismatchException stale) {
                throw GraphqlErrorException.newErrorException().message("Deployment schema must be refreshed").extensions(extensions()).build();
            }
        };
    }

    @Override
    public CompletableFuture<ExecutionResult> instrumentExecutionResult(ExecutionResult result, InstrumentationExecutionParameters parameters, InstrumentationState state) {
        var execution = (State) state;
        ApiRequestContext request = parameters.getGraphQLContext().get("request");
        // Mutations already committed under the transaction fence must retain their successful receipts.
        if (!execution.mutation && execution.applicationRead && request != null) {
            try { application.requireCurrentSchema(request.request(), request.principal()); }
            catch (SchemaVersionMismatchException stale) {
                result = failure("Deployment schema must be refreshed", "SCHEMA_VERSION_MISMATCH");
            } catch (SecurityException denied) {
                result = failure("Request identity does not match the authenticated principal", "FORBIDDEN");
            } catch (RuntimeException unavailable) {
                result = failure("Deployment schema could not be verified", "SCHEMA_BINDING_UNAVAILABLE");
            }
        }
        return CompletableFuture.completedFuture(result);
    }

    private static ExecutionResult failure(String message, String code) {
        return ExecutionResultImpl.newExecutionResult().data(null).errors(List.of(GraphqlErrorBuilder.newError()
                .message(message).extensions(Map.of("code", code, "retryable", false)).build())).build();
    }

    private static Map<String, Object> extensions() { return Map.of("code", "SCHEMA_VERSION_MISMATCH", "retryable", false); }
}
