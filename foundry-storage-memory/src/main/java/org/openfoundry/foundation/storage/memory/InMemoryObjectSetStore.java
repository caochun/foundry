package org.openfoundry.foundation.storage.memory;

import org.openfoundry.foundation.spi.*;
import java.time.Clock;
import java.util.*;

public final class InMemoryObjectSetStore implements ObjectSetStore {
    private final Clock clock;
    private final Map<Key, ObjectSetDefinition> definitions = new HashMap<>();

    public InMemoryObjectSetStore() { this(Clock.systemUTC()); }
    public InMemoryObjectSetStore(Clock clock) { this.clock = Objects.requireNonNull(clock); }

    @Override public synchronized ObjectSetDefinition create(RequestContext context, ObjectSetSpec spec) {
        if (context.actorId() == null) throw new SecurityException("ObjectSet creation requires an authenticated actor");
        var now = clock.instant();
        var definition = new ObjectSetDefinition(UUID.randomUUID().toString(), context.tenantId(), context.actorId(), now, now, 1, spec);
        definitions.put(new Key(context.tenantId(), definition.id()), definition);
        return definition;
    }
    @Override public synchronized ObjectSetDefinition get(RequestContext context, String id) {
        var definition = definitions.get(new Key(context.tenantId(), id));
        return definition != null && definition.visibleTo(context) ? definition : null;
    }
    @Override public synchronized ObjectSetDefinition getByName(RequestContext context, String name) {
        return list(context, null).stream().filter(definition -> definition.spec().name().equals(name)).findFirst().orElse(null);
    }
    @Override public synchronized List<ObjectSetDefinition> list(RequestContext context, String objectType) {
        return definitions.values().stream().filter(definition -> definition.visibleTo(context))
                .filter(definition -> objectType == null || definition.spec().objectType().equals(objectType))
                .sorted(Comparator.comparing(ObjectSetDefinition::createdAt).thenComparing(ObjectSetDefinition::id)).toList();
    }
    @Override public synchronized ObjectSetDefinition update(RequestContext context, String id, Map<String, Object> patch, Long expectedVersion) {
        var key = new Key(context.tenantId(), id);
        var existing = definitions.get(key);
        if (existing == null) throw new ObjectSetNotFoundException();
        existing.requireOwner(context, expectedVersion);
        var updated = existing.updated(existing.spec().patched(patch), clock.instant());
        definitions.put(key, updated);
        return updated;
    }
    @Override public synchronized void delete(RequestContext context, String id, Long expectedVersion) {
        var key = new Key(context.tenantId(), id);
        var existing = definitions.get(key);
        if (existing == null) throw new ObjectSetNotFoundException();
        existing.requireOwner(context, expectedVersion);
        definitions.remove(key);
    }
    private record Key(String tenant, String id) {}
}
