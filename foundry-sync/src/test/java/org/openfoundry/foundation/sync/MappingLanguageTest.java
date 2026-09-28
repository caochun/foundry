package org.openfoundry.foundation.sync;

import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.spi.*;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MappingLanguageTest {
    static final String YAML = """
            datasource: PAS_Patients
            connector: jdbc
            connection:
              url: "${PAS_DB_URL}"
              table: patients
              fetchSize: 500
            mapping:
              objectType: Patient
              primaryKey: {source: patient_id, target: id, transform: "prefix('patient-')"}
              properties:
                nhsNumber: {source: nhs_no}
                name: {source: surname, transform: "concat(title, ' ', forename, ' ', surname)"}
                dateOfBirth: {source: dob, transform: "parseDate('dd/MM/yyyy')"}
                status: {source: discharge_date, transform: "ifPresent('DISCHARGED', 'ACTIVE')"}
              links:
                - linkType: AdmittedTo
                  toType: Ward
                  toKey: {source: ward_code, target: id, transform: "prefix('ward-')"}
                  properties:
                    admissionDate: {source: admitted_at, transform: "parseDateTime('yyyy-MM-dd HH:mm:ss')"}
            sync:
              mode: CDC
              conflictResolution: SOURCE_PRIORITY
              rateLimit: {maxRecordsPerSecond: 500}
            """;

    @Test
    void upstreamPasShapeMapsPropertiesKeysAndRelationshipReferences() {
        var definition = new MappingConfigParser().parse(YAML);
        assertEquals("${PAS_DB_URL}", definition.connection().url());
        assertEquals(500, definition.connection().properties().get("fetchSize"));
        assertEquals(DatasourceMapping.Mode.CDC, definition.sync().mode());
        assertEquals(500, definition.sync().maxRecordsPerSecond());
        var input = new LinkedHashMap<String, Object>();
        input.put("patient_id", "12345");
        input.put("nhs_no", "943 476 5919");
        input.put("title", "Mr");
        input.put("forename", "John");
        input.put("surname", "Smith");
        input.put("dob", "15/03/1985");
        input.put("discharge_date", null);
        input.put("ward_code", "A1");
        input.put("admitted_at", "2026-01-15 09:30:00");
        var result = new RecordMapper(definition.mapping()).map(record(input));
        assertEquals(new EntityKey("Patient", "patient-12345"), result.key());
        assertEquals(Map.of("nhsNumber", "943 476 5919", "name", "Mr John Smith", "dateOfBirth", "1985-03-15", "status", "ACTIVE"), result.properties());
        assertEquals(new EntityKey("Ward", "ward-A1"), result.links().getFirst().target());
        assertEquals(Map.of("admissionDate", "2026-01-15T09:30:00Z"), result.links().getFirst().properties());
    }

    @Test
    void originalConnectorFilesRemainReadableWithoutConnectingOrExpandingSecrets() throws Exception {
        var parser = new MappingConfigParser();
        for (String name : List.of("pas-jdbc.yaml", "erp-jdbc.yaml", "tms-jdbc.yaml")) {
            try (var stream = getClass().getResourceAsStream("/upstream-mappings/" + name)) {
                assertNotNull(stream);
                var definition = parser.parse(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                assertEquals(DatasourceMapping.Mode.OVERLAY, definition.sync().mode());
                assertTrue(definition.connection().url().startsWith("${"));
                assertEquals(false, definition.sync().writeback());
                assertDoesNotThrow(() -> new RecordMapper(definition.mapping()));
            }
        }
    }

    @Test
    void allBuiltinsFollowTheUpstreamScalarContracts() {
        var registry = new TransformRegistry();
        var cases = List.of(
                new Case("prefix('id-')", 12, "id-12"), new Case("suffix('-uk')", "NHS", "NHS-uk"),
                new Case("parseDate('dd/MM/yyyy')", "29/02/2024", "2024-02-29"),
                new Case("parseDateTime('yyyy-MM-dd HH:mm:ss')", "2026-01-15 09:30:00", "2026-01-15T09:30:00Z"),
                new Case("toUpper()", "hello", "HELLO"), new Case("toLower()", "HELLO", "hello"),
                new Case("trim()", "\u00a0 hello \ufeff", "hello"), new Case("parseInt()", " -12items", -12),
                new Case("parseFloat()", "1.5e2x", 150.0), new Case("ifPresent('YES','NO')", "", "YES"),
                new Case("coalesce('fallback')", null, "fallback"), new Case("map({'A':'ACTIVE','D':'DONE'})", "A", "ACTIVE"));
        for (var item : cases) assertEquals(item.expected, registry.compile(item.expression).apply(item.value, Map.of()), item.expression);
        assertEquals("A, B:C", registry.compile("concat(first, ', ', second, ':C')").apply(null, Map.of("first", "A", "second", "B")));
        assertNull(registry.compile("parseInt()").apply("invalid", Map.of()));
        assertNull(registry.compile("parseFloat()").apply("Infinity", Map.of()));
        assertNull(registry.compile("map({'A':'YES'})").apply("unknown", Map.of()));
        for (String expression : List.of("prefix('x')", "suffix('x')", "parseDate('yyyy-MM-dd')", "parseInt()", "parseFloat()", "toUpper()", "toLower()", "trim()", "map({})")) {
            assertNull(registry.compile(expression).apply(null, Map.of()), expression);
        }
    }

    @Test
    void dateValidationRejectsImpossibleValuesAndDoesNotUseTheMachineTimeZone() {
        var registry = new TransformRegistry();
        assertThrows(RuntimeException.class, () -> registry.compile("parseDate('dd/MM/yyyy')").apply("31/02/2024", Map.of()));
        assertThrows(RuntimeException.class, () -> registry.compile("parseDateTime('yyyy-MM-dd HH:mm:ss')").apply("2026-01-01 24:00:00", Map.of()));
        assertThrows(RuntimeException.class, () -> registry.compile("parseDate('dd/MM/yyyy')").apply("2024-02-29", Map.of()));
        assertEquals("2024-02-29T00:00:00Z", registry.compile("parseDateTime('yyyy-MM-dd')").apply("2024-02-29", Map.of()));
        var original = Locale.getDefault();
        try { Locale.setDefault(Locale.forLanguageTag("tr-TR")); assertEquals("I", registry.compile("toUpper()").apply("i", Map.of())); }
        finally { Locale.setDefault(original); }
    }

    @Test
    void malformedExpressionsAreFullyParsedRatherThanPartiallyMatched() {
        var registry = new TransformRegistry();
        for (String expression : List.of("toUpper('extra')", "prefix()", "unknown('x')", "concat(a,)", "prefix('unterminated)",
                "map({'A':'B',garbage})", "map({'A':'B','A':'C'})", "map({A:B})", "trim(); System.exit(0)", "prefix(trim())", "parseDate('yyyy/yyyy')")) {
            assertThrows(RuntimeException.class, () -> registry.compile(expression), expression);
        }
        assertEquals("O'Neil:a,b", registry.compile("prefix('O\\'Neil:')").apply("a,b", Map.of()));
    }

    @Test
    void malformedYamlAndRecursiveDataAreRejected() {
        var parser = new MappingConfigParser();
        for (String source : List.of(YAML + "unknown: true\n", YAML.replace("mode: CDC", "mode: UNKNOWN"),
                YAML.replace("primaryKey: {source: patient_id, target: id, transform: \"prefix('patient-')\"}", "primaryKey: {source: patient_id}"),
                YAML.replace("maxRecordsPerSecond: 500", "maxRecordsPerSecond: 1.5"), YAML.replace("mode: CDC", "mode: CDC\n  writeback: 'false'"),
                YAML.replace("datasource: PAS_Patients", "datasource: PAS_Patients\ndatasource: duplicate"), YAML.replace("prefix('patient-')", "unknown()"))) {
            assertThrows(RuntimeException.class, () -> parser.parse(source));
        }
        var recursive = new LinkedHashMap<String, Object>();
        recursive.put("connection", recursive);
        assertThrows(IllegalArgumentException.class, () -> parser.parse(recursive));
    }

    @Test
    void registryIsImmutableAndCustomCodeRequiresExplicitVersioning() {
        var empty = new TransformRegistry();
        var registered = empty.with("fullName", "v1", (value, record) -> record.get("first") + " " + record.get("last"));
        assertThrows(IllegalArgumentException.class, () -> empty.compile("custom('fullName')"));
        assertEquals("Ada Lovelace", registered.compile("custom('fullName')").apply(null, Map.of("first", "Ada", "last", "Lovelace")));
        assertThrows(IllegalArgumentException.class, () -> registered.with("fullName", "v2", (value, record) -> value));
        assertThrows(IllegalArgumentException.class, () -> empty.with("bad", "", (value, record) -> value));
        var config = new MappingConfig("Person", new KeyMapping("id", "id", null), Map.of("name", new PropertyMapping("name", "custom('fullName')")), List.of());
        var first = new RecordMapper(config, registered);
        var second = new RecordMapper(config, empty.with("fullName", "v2", (value, record) -> value));
        assertNotEquals(first.fingerprint(), second.fingerprint());
        assertEquals(first.fingerprint(), new RecordMapper(config, registered.with("unused", "v1", (value, record) -> value)).fingerprint());
    }

    @Test
    void customTransformsCannotMutateInputOrReturnMutableEvidence() {
        var output = new ArrayList<Object>(List.of("original"));
        var registry = new TransformRegistry().with("copy", "1", (value, record) -> {
            assertThrows(UnsupportedOperationException.class, () -> record.put("injected", true));
            return output;
        });
        var mapping = new MappingConfig("Person", new KeyMapping("id", "id", null), Map.of("payload", new PropertyMapping("value", "custom('copy')")), List.of());
        var mapped = new RecordMapper(mapping, registry).map(record(Map.of("id", "a", "value", "input")));
        output.add("late mutation");
        assertEquals(List.of("original"), mapped.properties().get("payload"));
    }

    @Test
    void legacyFingerprintsAndMissingValueSemanticsRemainStable() {
        var config = new MappingConfig("Person", "source_id", Map.of("full_name", "name", "note", "note"));
        String old = LineageValues.hash(true, Map.of("type", "Person", "primary", "source_id", "fields", Map.of("full_name", "name", "note", "note")));
        assertEquals(old, new RecordMapper(config).fingerprint());
        var mapped = new RecordMapper(config).map(record(Map.of("source_id", "a", "full_name", "Name")));
        assertFalse(mapped.properties().containsKey("note"));
        var values = new LinkedHashMap<String, Object>(); values.put("source_id", "a"); values.put("note", null);
        assertTrue(new RecordMapper(config).map(record(values)).properties().containsKey("note"));
        var rich = new MappingConfig("Person", new KeyMapping("id", "id", null), Map.of("one", new PropertyMapping("same", "toUpper()"), "two", new PropertyMapping("same", "toLower()")), List.of());
        assertEquals(Map.of("one", "ABC", "two", "abc"), new RecordMapper(rich).map(record(Map.of("id", "a", "same", "Abc"))).properties());
        assertThrows(IllegalStateException.class, rich::sourceToTarget);
    }

    @Test
    void relationshipMissingNullAndInvalidIdentityHaveDifferentMeanings() {
        var config = new MappingConfigParser().parse(YAML).mapping();
        var mapper = new RecordMapper(config);
        var base = new LinkedHashMap<String, Object>(); base.put("patient_id", "a");
        assertTrue(mapper.map(record(base)).links().isEmpty());
        base.put("ward_code", null);
        assertTrue(mapper.map(record(base)).links().getFirst().clear());
        var invalid = new LinkMapping("AdmittedTo", "Ward", new KeyMapping("ward", "id", "map({'known':'ward-1'})"), Map.of());
        var strict = new RecordMapper(new MappingConfig("Person", new KeyMapping("id", "id", null), Map.of(), List.of(invalid)));
        assertThrows(IllegalArgumentException.class, () -> strict.map(record(Map.of("id", "a", "ward", "unknown"))));
    }

    @Test
    void deletionOnlyMapsIdentityAndNeverEvaluatesIrrelevantTransforms() {
        var config = new MappingConfigParser().parse(YAML).mapping();
        var input = new SourceRecord("test", "a", "DELETE", Instant.EPOCH, Map.of("patient_id", "a", "dob", "not a date"), null);
        var result = new RecordMapper(config).map(input);
        assertEquals("patient-a", result.key().id());
        assertTrue(result.properties().isEmpty());
        assertTrue(result.links().isEmpty());
    }

    static SourceRecord record(Map<String, Object> values) { return new SourceRecord("test", "row", "UPSERT", Instant.EPOCH, values, null); }
    private record Case(String expression, Object value, Object expected) {}
}
