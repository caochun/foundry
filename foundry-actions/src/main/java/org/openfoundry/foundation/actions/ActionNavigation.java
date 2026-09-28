package org.openfoundry.foundation.actions;

import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.*;

import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Transaction-local relationship projections plus durable evidence of the identities actually read. */
final class ActionNavigation {
    private static final Pattern PATH = Pattern.compile("[A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)+");
    private static final Pattern QUOTED = Pattern.compile("'(?:\\\\.|[^'\\\\])*'|\"(?:\\\\.|[^\"\\\\])*\"");
    private final OntologySchema schema;
    private final RequestContext context;
    private final ActionActor actor;
    private final ActionTypeDefinition definition;
    private final Map<String, Object> parameters;
    private final ActionAuthorizer authorizer;
    private final Transaction transaction;
    private final Supplier<List<ActionEffectAccess>> preceding;
    private final Map<String, Object> values = new LinkedHashMap<>();
    private final List<Map<String, Object>> journal;
    private int targetCount;

    ActionNavigation(OntologySchema schema, RequestContext context, ActionActor actor, ActionTypeDefinition definition,
                     Map<String, Object> parameters, ActionAuthorizer authorizer, Transaction transaction,
                     Supplier<List<ActionEffectAccess>> preceding, List<Map<String, Object>> journal) {
        this.schema = schema;
        this.context = context;
        this.actor = actor;
        this.definition = definition;
        this.parameters = parameters;
        this.authorizer = authorizer;
        this.transaction = transaction;
        this.preceding = preceding;
        this.journal = journal;
    }

    Object property(ObjectRecord source, String name, String path) {
        var field = field(schema, source.type(), name);
        if (field == null) return source.properties().get(name);
        path = canonicalPath(path);
        if (values.containsKey(path)) return values.get(path);
        if (path.split("\\.").length > 16 || journal.size() >= 256) throw new IllegalArgumentException("Action navigation budget exceeded");
        var sourceKey = source.key();
        // Check the source/field even when no relationship exists.
        requireAllowed(new ActionNavigationRead(source, field, List.of(), List.of()));
        boolean outgoing = field.direction() == StorageProvider.Direction.OUTBOUND;
        var links = transaction.findLinks(field.linkType(), outgoing ? sourceKey : null, outgoing ? null : sourceKey, field.history());
        targetCount += links.size();
        if (targetCount > 1000) throw new IllegalArgumentException("Action navigation target budget exceeded");
        var read = read(source, field, links, transaction);
        requireAllowed(read);
        if (!field.many() && links.size() > 1) throw new LinkResolutionException("LINK_RESOLUTION_AMBIGUOUS");
        List<?> selected = field.targetType().equals(field.linkType()) ? read.links() : read.targets();
        Object result = field.many() ? selected : selected.isEmpty() ? null : selected.getFirst();
        values.put(path, result);
        journal.add(Map.of("path", path, "type", source.type(), "id", source.id(), "field", field.name(),
                "declaration", ActionFingerprint.hash(field), "links", links.stream().map(LinkRecord::id).toList()));
        return result;
    }

    void preload(ActionManifest manifest, ActionValues expressions) {
        for (String path : paths(manifest, false)) expressions.prime(path);
    }

    void preloadCondition(String condition, ActionValues expressions) {
        if (condition != null) paths(condition).forEach(expressions::prime);
    }

    private void requireAllowed(ActionNavigationRead read) {
        if (!authorizer.allowedNavigation(context, actor, definition, parameters, read, preceding.get(), transaction)) {
            throw new SecurityException("Action relationship read denied");
        }
    }

    Map<String, Object> project(Map<String, Object> roots) {
        if (values.isEmpty()) return roots;
        var result = ActionValues.jsonMap(roots);
        for (var entry : values.entrySet()) {
            var parts = entry.getKey().split("\\.");
            if (!result.containsKey(parts[0])) continue;
            Object current = result.get(parts[0]);
            for (int index = 1; index < parts.length - 1; index++) {
                current = current instanceof Map<?, ?> map ? map.get(parts[index]) : null;
            }
            if (current instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked") var target = (Map<String, Object>) map;
                target.put(parts[parts.length - 1], ActionValues.jsonValue(entry.getValue()));
            }
        }
        return PropertyValues.immutableMap(result);
    }

    static void authorizeJournal(OntologySchema schema, ActionManifest manifest, ActionTypeDefinition definition,
                                 Map<String, Object> parameters, Object raw, Object digest, Object consentJournal, RequestContext context, ActionActor actor,
                                 ActionAuthorizer authorizer, List<ActionEffectAccess> effects, Transaction transaction) {
        if (raw == null) {
            if (digest != null || requiresJournal(schema, manifest, definition)) throw new IllegalStateException("Action navigation evidence is missing");
            return;
        }
        if (!(digest instanceof String expected) || !ActionFingerprint.hash(raw).equals(expected)) {
            throw new IllegalStateException("Action navigation evidence checksum mismatch");
        }
        var paths = new HashSet<String>();
        var reads = new LinkedHashMap<String, ActionNavigationRead>();
        var entries = ActionContinuationState.maps(raw);
        if (entries.size() > 256) throw new IllegalStateException("Action navigation evidence exceeds budget");
        for (var entry : entries) {
            if (!entry.keySet().equals(Set.of("path", "type", "id", "field", "declaration", "links"))) {
                throw new IllegalStateException("Invalid navigation evidence");
            }
            String path = text(entry, "path");
            if (!PATH.matcher(path).matches() || !paths.add(path)) throw new IllegalStateException("Invalid navigation path");
            var field = field(schema, text(entry, "type"), text(entry, "field"));
            if (field == null || !ActionFingerprint.hash(field).equals(text(entry, "declaration"))) {
                throw new IllegalStateException("Navigation declaration changed");
            }
            var source = transaction.getObject(text(entry, "type"), text(entry, "id"));
            if (source == null) throw new SecurityException("Navigation source is unavailable");
            if (!(entry.get("links") instanceof List<?> ids) || ids.size() > 1000 || new HashSet<>(ids).size() != ids.size()) {
                throw new IllegalStateException("Invalid navigation link identities");
            }
            var links = new ArrayList<LinkRecord>();
            for (Object id : ids) {
                if (!(id instanceof String value) || value.isBlank()) throw new IllegalStateException("Invalid navigation link identity");
                var link = transaction.getLink(field.linkType(), value);
                if (link == null) throw new SecurityException("Navigation relationship is unavailable");
                links.add(link);
            }
            var read = read(source, field, links, transaction);
            reads.put(path, read);
            if (!authorizer.allowedNavigation(context, actor, definition, parameters, read, effects, transaction)) {
                throw new SecurityException("Action navigation replay denied");
            }
        }
        requirePaths(schema, manifest, parameters, effects, reads, consentJournal, transaction);
    }

    private static void requirePaths(OntologySchema schema, ActionManifest manifest, Map<String, Object> parameters,
                                     List<ActionEffectAccess> effects, Map<String, ActionNavigationRead> reads, Object consentJournal, Transaction transaction) {
        var roots = new LinkedHashMap<>(parameters);
        for (var effect : effects) {
            if (effect.kind() != ActionEffectAccess.Kind.CREATE_OBJECT) continue;
            String type = effect.entity().type();
            String alias = Character.toLowerCase(type.charAt(0)) + type.substring(1);
            if (!roots.containsKey(alias)) roots.put(alias, transaction.getObject(type, effect.entity().id()));
        }
        var required = paths(manifest, false);
        var appliedConsent = new HashSet<Integer>();
        if (consentJournal != null) {
            for (var entry : ActionContinuationState.maps(consentJournal)) {
                if (Boolean.TRUE.equals(entry.get("applied")) && entry.get("effect") instanceof Number index) appliedConsent.add(index.intValue());
            }
        }
        for (int index = 0; index < manifest.effects().size(); index++) {
            if (manifest.effects().get(index) instanceof ActionManifest.RecordConsent consent
                    && (consent.condition() == null || appliedConsent.contains(index))) required.addAll(paths(consent.subject()));
        }
        for (String expression : required) {
            var parts = canonicalPath(expression).split("\\.");
            Object current = roots.get(parts[0]);
            String prefix = parts[0];
            for (int index = 1; index < parts.length && current instanceof ObjectRecord source; index++) {
                var field = field(schema, source.type(), parts[index]);
                if (field == null) break;
                prefix += "." + parts[index];
                var read = reads.get(prefix);
                if (read == null || !read.source().key().equals(source.key()) || !read.field().equals(field)) {
                    throw new IllegalStateException("Incomplete or inconsistent Action navigation evidence");
                }
                if (field.many() || field.targetType().equals(field.linkType())) break;
                current = read.targets().isEmpty() ? null : read.targets().getFirst();
            }
        }
    }

    private static ActionNavigationRead read(ObjectRecord source, LinkFieldDefinition field, List<LinkRecord> links, Transaction transaction) {
        var targets = new ArrayList<ObjectRecord>();
        boolean outgoing = field.direction() == StorageProvider.Direction.OUTBOUND;
        for (var link : links) {
            if (!link.type().equals(field.linkType()) || !(outgoing ? link.from() : link.to()).equals(source.key())) {
                throw new IllegalStateException("Navigation relationship does not match its source");
            }
            var key = outgoing ? link.to() : link.from();
            var target = transaction.getObject(key.type(), key.id());
            if (target == null || !source.tenantId().equals(target.tenantId())) throw new SecurityException("Navigation target is unavailable");
            targets.add(target);
        }
        return new ActionNavigationRead(source, field, links, targets);
    }

    private static LinkFieldDefinition field(OntologySchema schema, String type, String name) {
        if (schema == null) return null;
        return schema.objectTypes().stream().filter(object -> object.name().equals(type)).flatMap(object -> object.linkFields().stream())
                .filter(field -> field.name().equals(name)).findFirst().orElse(null);
    }

    static boolean requiresJournal(OntologySchema schema, ActionManifest manifest, ActionTypeDefinition definition) {
        if (schema == null) return false;
        var roots = new HashMap<String, String>();
        definition.parameters().forEach(parameter -> roots.put(parameter.name(), parameter.baseType()));
        manifest.effects().forEach(effect -> {
            if (effect instanceof ActionManifest.CreateObject create) {
                String type = create.objectType();
                roots.putIfAbsent(Character.toLowerCase(type.charAt(0)) + type.substring(1), type);
            }
        });
        for (String path : paths(manifest, true)) {
            var parts = canonicalPath(path).split("\\.");
            if (parts.length > 1 && field(schema, roots.get(parts[0]), parts[1]) != null) return true;
        }
        return false;
    }

    private static Set<String> paths(ActionManifest manifest, boolean includeConsentSubject) {
        var result = new LinkedHashSet<String>();
        manifest.preconditions().forEach(precondition -> result.addAll(paths(precondition.expression())));
        for (var effect : manifest.effects()) {
            if (effect instanceof ActionManifest.UpdateObject update) {
                result.addAll(paths(update.target()));
                update.set().values().forEach(value -> result.addAll(paths(value)));
            } else if (effect instanceof ActionManifest.CreateObject create) {
                if (create.target() != null) result.addAll(paths(create.target()));
                create.properties().values().forEach(value -> result.addAll(paths(value)));
            } else if (effect instanceof ActionManifest.CreateLink link) {
                result.addAll(paths(link.from()));
                result.addAll(paths(link.to()));
                link.properties().values().forEach(value -> result.addAll(paths(value)));
            } else if (effect instanceof ActionManifest.DeleteLink link) {
                if (link.linkId() != null) result.addAll(paths(link.linkId()));
                if (link.filter() != null) {
                    if (link.filter().from() != null) result.addAll(paths(link.filter().from()));
                    if (link.filter().to() != null) result.addAll(paths(link.filter().to()));
                }
            } else if (effect instanceof ActionManifest.RecordConsent consent) {
                if (consent.condition() != null) result.addAll(paths(consent.condition()));
                if (includeConsentSubject) result.addAll(paths(consent.subject()));
            }
        }
        manifest.sideEffects().forEach(effect -> {
            if (effect.type().equals("event")) collect(effect.config().get("data"), result);
            if (effect.type().equals("webhook")) collect(effect.config().get("body"), result);
        });
        return result;
    }

    private static void collect(Object value, Set<String> paths) {
        if (value instanceof String expression) paths.addAll(paths(expression));
        else if (value instanceof Map<?, ?> map) map.values().forEach(item -> collect(item, paths));
        else if (value instanceof List<?> list) list.forEach(item -> collect(item, paths));
    }

    private static Set<String> paths(String expression) {
        var result = new LinkedHashSet<String>();
        var matcher = PATH.matcher(QUOTED.matcher(expression).replaceAll(" "));
        while (matcher.find()) result.add(matcher.group());
        return result;
    }

    private static String canonicalPath(String path) { return path.startsWith("params.") ? path.substring(7) : path; }
    private static String text(Map<String, Object> entry, String field) {
        if (!(entry.get(field) instanceof String value) || value.isBlank()) throw new IllegalStateException("Invalid navigation evidence field");
        return value;
    }
}
