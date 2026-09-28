package org.openfoundry.foundation.actions;

import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.ActionTypeDefinition;
import java.util.*;

/** Consent effects are metadata changes; they do not pretend to update an object's fact version. */
final class ConsentEffects {
    final ConsentStore store;
    final String defaultPurpose;
    final Set<String> subjectTypes;
    final Set<String> purposes;

    ConsentEffects(ConsentStore store, String defaultPurpose, Set<String> subjectTypes, Set<String> purposes) {
        this.store = Objects.requireNonNull(store);
        this.defaultPurpose = Objects.requireNonNull(defaultPurpose);
        this.subjectTypes = Set.copyOf(subjectTypes);
        this.purposes = Set.copyOf(purposes);
        if (this.subjectTypes.isEmpty() || !this.purposes.contains(defaultPurpose)) throw new IllegalArgumentException("Invalid consent effect configuration");
        store.initialize();
    }

    static boolean present(ActionManifest manifest) {
        return manifest.effects().stream().anyMatch(effect -> effect instanceof ActionManifest.RecordConsent);
    }

    ActionManifest configured(ActionManifest manifest) {
        if (!present(manifest)) return manifest;
        var effects = manifest.effects().stream().map(effect -> {
            if (!(effect instanceof ActionManifest.RecordConsent record)) return effect;
            String type = record.subjectType() == null ? fallbackType() : record.subjectType();
            String purpose = record.purpose() == null ? defaultPurpose : record.purpose();
            if (type != null && !subjectTypes.contains(type) || !purposes.contains(purpose)) throw new IllegalArgumentException("Consent effect configuration is not permitted");
            return (ActionManifest.ActionEffect) new ActionManifest.RecordConsent(record.subject(), type, purpose, record.decision(), record.evidence(), record.condition());
        }).toList();
        return new ActionManifest(manifest.action(), manifest.version(), manifest.reversible(), manifest.preconditions(), effects, manifest.onSideEffectFailure(), manifest.sideEffects());
    }

    String fallbackType() {
        return subjectTypes.size() == 1 ? subjectTypes.iterator().next() : null;
    }

    Map<String, Object> apply(Pending pending, RequestContext context, ActionActor actor, ActionTypeDefinition definition,
                              Map<String, Object> parameters, ActionAuthorizer authorizer, Transaction transaction) {
        var effect = pending.effect();
        var subject = pending.subject();
        String purpose = effect.purpose() == null ? defaultPurpose : effect.purpose();
        authorize(context, actor, definition, parameters, authorizer, subject, purpose, transaction);
        store.prepareTransaction(context, subject, transaction);
        authorize(context, actor, definition, parameters, authorizer, subject, purpose, transaction);
        var before = store.snapshot(context, subject, transaction);
        var previous = before.records().stream().filter(record -> record.purpose().equals(purpose)).reduce((a, b) -> b).orElse(null);
        var recorded = store.record(context, subject, purpose, effect.decision(), effect.evidence(), transaction);
        authorize(context, actor, definition, parameters, authorizer, subject, purpose, transaction);
        var entry = new LinkedHashMap<String, Object>();
        entry.put("effect", pending.index());
        entry.put("applied", true);
        entry.put("type", subject.type());
        entry.put("id", subject.id());
        entry.put("purpose", purpose);
        entry.put("version", recorded.sequence());
        entry.put("decision", recorded.decision().name());
        entry.put("before", previous == null ? null : previous.decision().name());
        return Collections.unmodifiableMap(entry);
    }

    void authorizeJournal(ActionManifest manifest, Object journal, RequestContext context, ActionActor actor, ActionTypeDefinition definition,
                          Map<String, Object> parameters, ActionAuthorizer authorizer, Transaction transaction) {
        for (var entry : entries(manifest, journal)) {
            if (Boolean.TRUE.equals(entry.get("applied"))) {
                authorize(context, actor, definition, parameters, authorizer, key(entry), text(entry, "purpose"), transaction);
            }
        }
    }
    void compensate(ActionManifest manifest, Object journal, String actionId, RequestContext context, Transaction transaction) {
        var checked = new HashSet<EntityKey>();
        var entries = entries(manifest, journal);
        for (int index = entries.size() - 1; index >= 0; index--) {
            var entry = entries.get(index);
            if (!Boolean.TRUE.equals(entry.get("applied"))) continue;
            var subject = key(entry);
            store.prepareTransaction(context, subject, transaction);
            var current = store.snapshot(context, subject, transaction);
            long original = ((Number) entry.get("version")).longValue();
            if (checked.add(subject) && current.revision() != original) throw new IllegalStateException("Consent compensation version conflict");
            var decision = entry.get("before") == null ? ConsentRecord.Decision.DENY : ConsentRecord.Decision.valueOf((String) entry.get("before"));
            store.restore(context, subject, text(entry, "purpose"), decision, "Compensation of " + actionId
                    + (entry.get("before") == null ? "; previous explicit decision absent" : "; restore previous explicit decision"), current.revision(), transaction);
        }
    }
    private void authorize(RequestContext context, ActionActor actor, ActionTypeDefinition definition, Map<String, Object> parameters,
                           ActionAuthorizer authorizer, EntityKey subject, String purpose, Transaction transaction) {
        if (!subjectTypes.contains(subject.type()) || !purposes.contains(purpose)) throw new IllegalArgumentException("Consent effect subject or purpose is not configured");
        if (!authorizer.allowedConsent(context, actor, definition, parameters, subject, purpose, transaction)) throw new SecurityException("Consent effect denied");
    }
    static List<Map<String, Object>> entries(ActionManifest manifest, Object value) {
        if (!present(manifest)) {
            if (value != null && !ActionContinuationState.maps(value).isEmpty()) throw new IllegalStateException("Unexpected consent journal");
            return List.of();
        }
        var entries = ActionContinuationState.maps(value);
        var expected = new ArrayList<Integer>();
        for (int index = 0; index < manifest.effects().size(); index++) {
            if (manifest.effects().get(index) instanceof ActionManifest.RecordConsent) expected.add(index);
        }
        if (entries.size() != expected.size()) throw new IllegalStateException("Incomplete consent journal");
        for (int index = 0; index < entries.size(); index++) {
            var entry = entries.get(index);
            if (!(entry.get("effect") instanceof Integer || entry.get("effect") instanceof Long)
                    || ((Number) entry.get("effect")).longValue() != expected.get(index) || !(entry.get("applied") instanceof Boolean)) throw new IllegalStateException("Invalid consent journal index");
            if (Boolean.FALSE.equals(entry.get("applied"))) {
                if (!entry.keySet().equals(Set.of("effect", "applied"))) throw new IllegalStateException("Invalid skipped consent entry");
                continue;
            }
            if (!entry.keySet().equals(Set.of("effect", "applied", "type", "id", "purpose", "version", "decision", "before"))) throw new IllegalStateException("Invalid consent journal");
            var declaration = (ActionManifest.RecordConsent) manifest.effects().get(expected.get(index));
            key(entry);
            if (!declaration.purpose().equals(text(entry, "purpose")) || !declaration.decision().name().equals(text(entry, "decision"))
                    || declaration.subjectType() != null && !declaration.subjectType().equals(text(entry, "type"))) throw new IllegalStateException("Consent journal disagrees with its declaration");
            if (!(entry.get("version") instanceof Integer || entry.get("version") instanceof Long) || ((Number) entry.get("version")).longValue() < 1) throw new IllegalStateException("Invalid consent journal version");
            if (entry.get("before") != null) ConsentRecord.Decision.valueOf(text(entry, "before"));
        }
        return entries;
    }
    private static EntityKey key(Map<String, Object> entry) { return new EntityKey(text(entry, "type"), text(entry, "id")); }
    private static String text(Map<String, Object> entry, String name) {
        if (!(entry.get(name) instanceof String value) || value.isBlank()) throw new IllegalStateException("Invalid consent journal field");
        return value;
    }
    record Pending(ActionManifest.RecordConsent effect, EntityKey subject, int index) {}
}
