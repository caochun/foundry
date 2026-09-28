package org.openfoundry.foundation.api;

import org.openfoundry.foundation.security.*;
import org.openfoundry.foundation.spi.*;
import java.util.*;

final class ConsentApi {
    private final ApplicationService application;
    ConsentApi(ApplicationService application) { this.application = application; }

    Map<String, Object> record(ApiRequestContext context, Map<String, Object> input) {
        fields(input, Set.of("subject", "subjectType", "purpose", "decision", "evidence"));
        var service = service(context);
        var subject = subject(input, service);
        String purpose = input.get("purpose") == null ? service.configuration().purpose() : text(input.get("purpose"));
        var decision = input.get("decision") == null ? ConsentRecord.Decision.GRANT : ConsentRecord.Decision.valueOf(text(input.get("decision")).toUpperCase(Locale.ROOT));
        String evidence = input.get("evidence") == null ? null : text(input.get("evidence"));
        return result(service.record(context.request(), context.principal(), subject, purpose, decision, evidence));
    }
    Map<String, Object> revoke(ApiRequestContext context, Map<String, Object> input) {
        fields(input, Set.of("subject", "subjectType", "purpose", "reason"));
        var service = service(context);
        var subject = subject(input, service);
        String purpose = input.get("purpose") == null ? service.configuration().purpose() : text(input.get("purpose"));
        var response = new LinkedHashMap<>(result(service.revoke(context.request(), context.principal(), subject, purpose, text(input.get("reason")))));
        response.put("liveInvalidationSupported", false);
        return response;
    }
    Map<String, Object> optOut(ApiRequestContext context, Map<String, Object> input) {
        fields(input, Set.of("subject", "subjectType", "optedOut", "reason"));
        var service = service(context);
        var subject = subject(input, service);
        if (!(input.get("optedOut") instanceof Boolean optedOut)) throw new IllegalArgumentException("optedOut requires a boolean");
        service.setOptOut(context.request(), context.principal(), subject, optedOut, text(input.get("reason")));
        return Map.of("subject", subject.id(), "subjectType", subject.type(), "optedOut", optedOut, "recorded", true);
    }
    Object records(ApiRequestContext context, Map<String, Object> input, boolean audit) {
        fields(input, Set.of("subject", "subjectType"));
        var service = service(context);
        var subject = subject(input, service);
        if (audit) return service.auditHistory(context.request(), context.principal(), subject).stream().map(entry -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", entry.id()); row.put("subject", subject.id()); row.put("subjectType", subject.type());
            row.put("purpose", entry.purpose()); row.put("operation", entry.operation()); row.put("outcome", entry.outcome());
            row.put("actor", entry.actorId()); row.put("traceId", entry.traceId()); row.put("recordedAt", entry.recordedAt().toString()); row.put("detail", entry.detail());
            return row;
        }).toList();
        var snapshot = service.records(context.request(), context.principal(), subject);
        return Map.of("subject", subject.id(), "subjectType", subject.type(), "optedOut", snapshot.optedOut(), "revision", snapshot.revision(),
                "records", snapshot.records().stream().map(ConsentApi::result).toList());
    }
    private ConsentService service(ApiRequestContext context) {
        var service = application.consentService(context.request(), context.principal());
        if (service == null) throw new NotConfigured();
        return service;
    }
    private static EntityKey subject(Map<String, Object> input, ConsentService service) {
        String type;
        if (input.get("subjectType") != null) type = text(input.get("subjectType"));
        else {
            if (service.configuration().subjectTypes().size() != 1) throw new IllegalArgumentException("subjectType is required for multiple consent subject types");
            type = service.configuration().subjectTypes().iterator().next();
        }
        return new EntityKey(type, text(input.get("subject")));
    }
    private static Map<String, Object> result(ConsentRecord record) {
        var result = new LinkedHashMap<String, Object>();
        result.put("subject", record.subject().id()); result.put("subjectType", record.subject().type()); result.put("purpose", record.purpose());
        result.put("decision", record.decision().name()); result.put("recorded", true); result.put("sequence", Long.toString(record.sequence()));
        result.put("recordedAt", record.recordedAt().toString()); result.put("recordedBy", record.recordedBy()); result.put("evidence", record.evidence());
        return result;
    }
    private static void fields(Map<String, Object> input, Set<String> allowed) {
        if (input == null || !allowed.containsAll(input.keySet())) throw new IllegalArgumentException("Unknown consent request field");
    }
    private static String text(Object value) {
        if (!(value instanceof String text)) throw new IllegalArgumentException("Consent value must be text");
        return text;
    }
    static final class NotConfigured extends IllegalStateException {}
}
