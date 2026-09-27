package org.openfoundry.foundation.api;

import graphql.ExecutionInput;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.openfoundry.foundation.actions.ActionExecutor;
import org.openfoundry.foundation.schema.OdlParser;
import org.openfoundry.foundation.security.*;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.spi.schema.PropertyValidationException;
import org.openfoundry.foundation.storage.jdbc.*;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ComputedFieldReadTest {
    static final OntologySchema SCHEMA = new OdlParser().parse("""
            extend schema @namespace(name: "computed", version: "1.0.0")
            interface Occupancy { occupancy: Int! @computed(fn: "countLinks", args: {type: "AdmittedTo"}, cache: LAZY) }
            type Ward implements Occupancy @objectType {
              id: ID! @primary name: String!
              privateCount: Int @computed(fn: "countLinks", args: {type: "AdmittedTo"}) @sensitive
            }
            type Patient @objectType {
              id: ID! @primary name: String!
              admissions: Int @computed(fn: "countLinks", args: {type: "AdmittedTo", direction: OUTBOUND})
            }
            type AdmittedTo @linkType(from: "Patient", to: "Ward", cardinality: MANY_TO_MANY) { id: ID! @primary }
            """);
    static final RequestContext CONTEXT = RequestContext.system("tenant", "reader");
    static final SecurityPrincipal PRINCIPAL = new SecurityPrincipal("reader", "tenant", Set.of());
    static final EntityKey WARD = new EntityKey("Ward", "w");
    static final EntityKey PATIENT = new EntityKey("Patient", "p");

    @TestFactory
    Stream<DynamicTest> computedReadsAcrossProviders() {
        return Stream.of("memory", "jdbc").flatMap(provider -> Stream.of(
                test(provider, "lazy reads change after relationship mutations without persisting computed attributes", this::lazy),
                test(provider, "counts cross storage pages and count relationships rather than distinct endpoints", this::paging),
                test(provider, "source edge target and field permissions constrain public counts", this::authorization),
                test(provider, "GraphQL interface and REST expose the declared Int projection", this::api),
                test(provider, "temporal lists use the same time for edges and endpoints", this::temporal),
                test(provider, "tenant identity and virtual-field writes are enforced", this::isolation)));
    }

    private DynamicTest test(String provider, String label, Consumer<Fixture> test) {
        return DynamicTest.dynamicTest(provider + " " + label, () -> {
            var clock = new TestClock();
            StorageProvider storage;
            if (provider.equals("memory")) storage = new InMemoryStorageProvider(clock);
            else {
                var data = new JdbcDataSource();
                data.setURL("jdbc:h2:mem:computed_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
                storage = new JdbcStorageProvider(data, DatabaseDialect.h2(), clock);
            }
            storage.applySchema(CONTEXT, SCHEMA);
            try (var tx = storage.beginTransaction(CONTEXT)) {
                tx.createObject("Ward", "w", Map.of("name", "Ward"));
                tx.createObject("Patient", "p", Map.of("name", "Patient"));
                tx.createLink("AdmittedTo", "edge", PATIENT, WARD, Map.of());
                tx.commit();
            }
            try { test.accept(new Fixture(storage, clock)); }
            finally { if (storage instanceof AutoCloseable resource) resource.close(); }
        });
    }

    private void lazy(Fixture f) {
        var app = f.app(Map.of());
        assertEquals(1, app.getObject(CONTEXT, PRINCIPAL, "Ward", "w").properties().get("occupancy"));
        assertEquals(1, app.getObject(CONTEXT, PRINCIPAL, "Patient", "p").properties().get("admissions"));
        f.clock.advance(1);
        try (var tx = f.storage.beginTransaction(CONTEXT)) { tx.deleteLink("AdmittedTo", "edge", 1); tx.commit(); }
        assertEquals(0, app.getObject(CONTEXT, PRINCIPAL, "Ward", "w").properties().get("occupancy"));
        f.clock.advance(1);
        try (var tx = f.storage.beginTransaction(CONTEXT)) { tx.restoreLink("AdmittedTo", "edge", 2); tx.commit(); }
        assertEquals(1, app.getObject(CONTEXT, PRINCIPAL, "Ward", "w").properties().get("occupancy"));
        assertEquals(1, f.storage.getObject(CONTEXT, "Ward", "w").version());
        assertFalse(f.storage.getObject(CONTEXT, "Ward", "w").properties().containsKey("occupancy"));
        assertTrue(app.history(CONTEXT, PRINCIPAL, WARD).stream().noneMatch(row -> row.state().containsKey("occupancy")));
    }

    private void paging(Fixture f) {
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            for (int i = 0; i < 125; i++) tx.createLink("AdmittedTo", "extra-" + i, PATIENT, WARD, Map.of());
            tx.commit();
        }
        assertEquals(126, new ComputedFieldEvaluator(f.storage, SCHEMA).evaluate(CONTEXT, WARD, "occupancy"));
        assertEquals(126, f.app(Map.of()).readComputedField(CONTEXT, PRINCIPAL, WARD, "occupancy"));
    }

    private void authorization(Fixture f) {
        var app = f.app(Map.of());
        f.denied.add(PATIENT);
        assertEquals(0, app.readComputedField(CONTEXT, PRINCIPAL, WARD, "occupancy"));
        assertEquals(1, new ComputedFieldEvaluator(f.storage, SCHEMA).evaluate(CONTEXT, WARD, "occupancy"));
        f.denied.clear();
        f.denied.add(new EntityKey("AdmittedTo", "edge"));
        assertEquals(0, app.readComputedField(CONTEXT, PRINCIPAL, WARD, "occupancy"));
        f.denied.clear();
        assertEquals(1, app.readComputedField(CONTEXT, PRINCIPAL, WARD, "occupancy"));
        f.denied.add(WARD);
        assertNull(app.readComputedField(CONTEXT, PRINCIPAL, WARD, "occupancy"));
        f.denied.clear();
        assertNull(app.readComputedField(CONTEXT, PRINCIPAL, WARD, "privateCount"));
        var explicit = f.app(Map.of("Ward", new FieldPolicy(Set.of("name"), Map.of("stats", Set.of("occupancy", "privateCount")))));
        assertNull(explicit.readComputedField(CONTEXT, PRINCIPAL, WARD, "occupancy"));
        assertEquals(1, explicit.readComputedField(CONTEXT, new SecurityPrincipal("reader", "tenant", Set.of("stats")), WARD, "privateCount"));
        var storedOnly = f.app(Map.of("Ward", FieldPolicy.storedFields(Set.of("name"), Map.of())));
        assertEquals(1, storedOnly.readComputedField(CONTEXT, PRINCIPAL, WARD, "occupancy"));
        assertNull(storedOnly.readComputedField(CONTEXT, PRINCIPAL, WARD, "privateCount"));
    }

    private void api(Fixture f) {
        var app = f.app(Map.of());
        var graph = GraphqlApiRuntime.create(SCHEMA, app);
        var result = graph.execute(ExecutionInput.newExecutionInput("{ ward(id: \"w\") { id name ... on Occupancy { occupancy } privateCount } }")
                .graphQLContext(Map.of("request", new ApiRequestContext(CONTEXT, PRINCIPAL))).build());
        assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
        Map<?, ?> data = result.getData();
        var ward = (Map<?, ?>) data.get("ward");
        assertEquals(1, ward.get("occupancy"));
        assertNull(ward.get("privateCount"));
        assertEquals("Int", ((graphql.schema.GraphQLNamedType) graph.getGraphQLSchema().getObjectType("Ward").getFieldDefinition("occupancy").getType()).getName());
        var response = new RestApiRouter(app).get(CONTEXT, PRINCIPAL, "/api/v1/Ward/w/computed/occupancy", QueryOptions.defaults());
        assertEquals(200, response.status());
        assertEquals(1, response.body());
        assertTrue(new GraphqlContractGenerator().generate(SCHEMA).contains("occupancy: Int"));
    }

    private void temporal(Fixture f) {
        f.clock.advance(10);
        try (var tx = f.storage.beginTransaction(CONTEXT)) { tx.deleteLink("AdmittedTo", "edge", 1); tx.commit(); }
        f.clock.advance(10);
        try (var tx = f.storage.beginTransaction(CONTEXT)) { tx.restoreLink("AdmittedTo", "edge", 2); tx.commit(); }
        f.clock.advance(10);
        try (var tx = f.storage.beginTransaction(CONTEXT)) { tx.deleteObject("Patient", "p", 1); tx.commit(); }
        var app = f.app(Map.of());
        assertEquals(0, app.getObject(CONTEXT, PRINCIPAL, "Ward", "w").properties().get("occupancy"));
        assertEquals(1, app.listObjects(CONTEXT, PRINCIPAL, "Ward", at(f, 5)).getFirst().properties().get("occupancy"));
        assertEquals(0, app.listObjects(CONTEXT, PRINCIPAL, "Ward", at(f, 15)).getFirst().properties().get("occupancy"));
        assertEquals(1, app.listObjects(CONTEXT, PRINCIPAL, "Ward", at(f, 25)).getFirst().properties().get("occupancy"));
        assertEquals(1, new RestApiRouter(app).get(CONTEXT, PRINCIPAL, "/api/v1/Ward/w/computed/occupancy", at(f, 25)).body());
        assertEquals(0, app.listObjects(CONTEXT, PRINCIPAL, "Ward", at(f, 30)).getFirst().properties().get("occupancy"));
    }

    private QueryOptions at(Fixture f, int seconds) {
        return new QueryOptions(100, 0, TestClock.START.plusSeconds(seconds), f.clock.instant(), false);
    }

    private void isolation(Fixture f) {
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            assertThrows(PropertyValidationException.class, () -> tx.updateObject("Ward", "w", Map.of("occupancy", 99), 1));
        }
        var other = RequestContext.system("other", "reader");
        try (var tx = f.storage.beginTransaction(other)) { tx.createObject("Ward", "w", Map.of("name", "Other")); tx.commit(); }
        var app = f.app(Map.of());
        assertEquals(0, app.getObject(other, new SecurityPrincipal("reader", "other", Set.of()), "Ward", "w").properties().get("occupancy"));
        assertThrows(SecurityException.class, () -> app.readComputedField(other, PRINCIPAL, WARD, "occupancy"));
        assertThrows(IllegalArgumentException.class, () -> app.readComputedField(CONTEXT, PRINCIPAL, WARD, "missing"));
    }

    private static final class Fixture {
        final StorageProvider storage;
        final TestClock clock;
        final Set<EntityKey> denied = new HashSet<>();
        Fixture(StorageProvider storage, TestClock clock) { this.storage = storage; this.clock = clock; }
        ApplicationService app(Map<String, FieldPolicy> policies) {
            return new ApplicationService(storage, new AuthorizationService((principal, relation, key) -> !denied.contains(key)),
                    new ActionExecutor(), SCHEMA, Map.of(), policies);
        }
    }
    private static final class TestClock extends Clock {
        static final Instant START = Instant.parse("2030-01-01T00:00:00Z");
        Instant now = START;
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
