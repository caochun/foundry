package org.openfoundry.foundation.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PackCompositionTest {
    @TempDir Path temporary;

    @Test
    void resolvesDependencyInterfacesEnumsAndRelationshipTypesBeforeCompilation() throws Exception {
        var core = pack("core", "example.core", "1.0.0", "", """
                interface Identifiable { id: ID! @primary }
                enum Status { READY DONE }
                type Organization implements Identifiable @objectType { name: String! }
                """);
        var app = pack("app", "example.app", "1.0.0", "dependencies: {example.core: '>=1.0.0'}\n", """
                type Person implements Identifiable @objectType {
                  name: String! status: Status! @default(value: READY)
                  organization: Organization @link(type: "WorksFor")
                }
                type WorksFor implements Identifiable @linkType(from: "Person", to: "Organization", cardinality: MANY_TO_ONE) {}
                """);
        var loader = new DomainPackLoader();
        var bundle = loader.loadBundle(List.of(app, core));
        var reordered = loader.loadBundle(List.of(core, app));
        assertEquals(bundle.digest(), reordered.digest());
        assertEquals(bundle.ontology().schemaDigest(), reordered.ontology().schemaDigest());
        assertEquals(List.of("example.core", "example.app"), bundle.manifests().stream().map(PackManifest::namespace).toList());
        assertEquals("example.core", bundle.typeOwners().get("Organization"));
        var person = bundle.ontology().schema().objectTypes().stream().filter(type -> type.name().equals("Person")).findFirst().orElseThrow();
        assertTrue(person.properties().stream().anyMatch(field -> field.primary() && field.name().equals("id")));
        assertEquals("Organization", person.linkFields().getFirst().targetType());
        assertEquals(List.of("READY", "DONE"), bundle.ontology().schema().enums().get("Status"));
        Files.writeString(app.resolve("schema.odl"), header("example.app", "1.0.0")
                + "type Person implements Identifiable @objectType { id: String name: String }");
        assertThrows(PackLoadException.class, () -> loader.loadBundle(List.of(core, app)));
    }

    @Test
    void undeclaredMissingConflictingAndCyclicDependenciesAreRejected() throws Exception {
        var core = pack("core", "example.core", "1.0.0", "", "interface Identifiable { id: ID! @primary }");
        var app = pack("app", "example.app", "1.0.0", "", "type Item implements Identifiable @objectType { name: String }");
        assertThrows(PackLoadException.class, () -> new DomainPackLoader().loadBundle(List.of(core, app)));
        Files.writeString(app.resolve("pack.yaml"), manifest("app", "example.app", "1.0.0", "dependencies: {example.core: '>=2.0.0'}\n"));
        assertThrows(PackLoadException.class, () -> new DomainPackLoader().loadBundle(List.of(core, app)));
        assertThrows(PackLoadException.class, () -> new DomainPackLoader().loadBundle(List.of(app)));
        Files.writeString(app.resolve("pack.yaml"), manifest("app", "example.app", "1.0.0", "dependencies: {example.core: '>=1.0.0'}\n"));
        Files.writeString(core.resolve("pack.yaml"), manifest("core", "example.core", "1.0.0", "dependencies: {example.app: '>=1.0.0'}\n"));
        assertThrows(PackLoadException.class, () -> new DomainPackLoader().loadBundle(List.of(core, app)));
    }

    @Test
    void versionsUsePrereleaseOrderingAndUnsupportedConstraintsDoNotPass() throws Exception {
        var core = pack("core", "example.core", "1.0.0-rc.2", "", "interface Identifiable { id: ID! @primary }");
        var app = pack("app", "example.app", "1.0.0", "dependencies: {example.core: '>=1.0.0-rc.1'}\n", "type Item implements Identifiable @objectType { name: String }");
        assertDoesNotThrow(() -> new DomainPackLoader().loadBundle(List.of(core, app)));
        for (String required : List.of(">=1.0.0", "^1.0.0", ">=1.0.0-rc.3", "1.0.0-rc.01")) {
            Files.writeString(app.resolve("pack.yaml"), manifest("app", "example.app", "1.0.0", "dependencies: {example.core: '" + required + "'}\n"));
            assertThrows(PackLoadException.class, () -> new DomainPackLoader().loadBundle(List.of(core, app)), required);
        }
    }

    @Test
    void conflictingTypesAndSourceNamespaceVersionDriftCannotBeHiddenByLoadOrder() throws Exception {
        var first = pack("first", "example.first", "1.0.0", "", "type Item @objectType { id: ID! @primary }");
        var second = pack("second", "example.second", "1.0.0", "", "type Item @objectType { id: ID! @primary }");
        assertThrows(PackLoadException.class, () -> new DomainPackLoader().loadBundle(List.of(first, second)));
        Files.writeString(first.resolve("schema.odl"), header("other.namespace", "1.0.0") + "type Item @objectType { id: ID! @primary }");
        assertThrows(PackLoadException.class, () -> new DomainPackLoader().load(first));
        Files.writeString(first.resolve("schema.odl"), header("example.first", "2.0.0") + "type Item @objectType { id: ID! @primary }");
        assertThrows(PackLoadException.class, () -> new DomainPackLoader().load(first));
        Files.writeString(first.resolve("schema.odl"), header("example.first", "1.0.0") + header("example.first", "2.0.0"));
        assertThrows(PackLoadException.class, () -> new DomainPackLoader().load(first));
    }

    @Test
    void assetsAndMetadataAreRetainedAndPoliciesApplyOnlyToStoredFields() throws Exception {
        var app = pack("app", "example.app", "1.0.0", """
                permissions: [permissions/model.fga]
                seed: [seed.yaml]
                connectors: [connector.yaml]
                capabilities: [example]
                x-business-contracts: [business.yaml]
                """, "type Item @objectType { id: ID! @primary name: String! secret: String @sensitive date: Date }");
        Files.createDirectories(app.resolve("permissions"));
        Files.writeString(app.resolve("permissions/model.fga"), "model\n  schema 1.1\ntype user\n");
        Files.writeString(app.resolve("permissions/field-permissions.yaml"), "- objectType: Item\n  alwaysVisible: [id, name]\n  fieldsByRelation: {manager: [secret]}\n");
        Files.writeString(app.resolve("seed.yaml"), "objects: [{type: Item, ref: initial, fields: {name: Test, date: 2030-01-01}}]\n");
        Files.writeString(app.resolve("connector.yaml"), "connector: rest\nurl: https://example.invalid/data\n");
        var bundle = new DomainPackLoader().loadBundle(List.of(app));
        assertTrue(bundle.assets().fieldPolicies().get("Item").storedFieldsOnly());
        assertEquals(java.util.Set.of("secret"), bundle.assets().fieldPolicies().get("Item").fieldsByRole().get("manager"));
        assertEquals("2030-01-01", bundle.assets().seeds().getFirst().objects().getFirst().fields().get("date"));
        assertEquals("rest", bundle.assets().connectors().getFirst().connector());
        assertEquals(List.of("business.yaml"), bundle.manifests().getFirst().metadata().get("x-business-contracts"));
        String digest = bundle.digest();
        Files.writeString(app.resolve("connector.yaml"), "connector: rest\nurl: https://example.invalid/changed\n");
        assertNotEquals(digest, new DomainPackLoader().loadBundle(List.of(app)).digest());
        Files.writeString(app.resolve("permissions/field-permissions.yaml"), "- objectType: Item\n  alwaysVisible: [missing]\n");
        assertThrows(PackLoadException.class, () -> new DomainPackLoader().loadBundle(List.of(app)));
    }

    @Test
    void assetReferencesAlsoRequireDeclaredDependencies() throws Exception {
        var core = pack("core", "example.core", "1.0.0", "", "type Organization @objectType { id: ID! @primary name: String }");
        var app = pack("app", "example.app", "1.0.0", "", "type Item @objectType { id: ID! @primary }");
        Files.createDirectories(app.resolve("permissions"));
        Files.writeString(app.resolve("permissions/field-permissions.yaml"), "- objectType: Organization\n  alwaysVisible: [id, name]\n");
        assertThrows(PackLoadException.class, () -> new DomainPackLoader().loadBundle(List.of(core, app)));
        Files.writeString(app.resolve("pack.yaml"), manifest("app", "example.app", "1.0.0", "dependencies: {example.core: '>=1.0.0'}\n"));
        assertDoesNotThrow(() -> new DomainPackLoader().loadBundle(List.of(core, app)));
    }

    @Test
    void computedRelationshipReferencesParticipateInPackVisibility() throws Exception {
        var core = pack("core", "example.core", "1.0.0", "", """
                type Item @objectType { id: ID! @primary }
                type Edge @linkType(from: "Item", to: "Item", cardinality: MANY_TO_MANY) { id: ID! @primary }
                """);
        var app = pack("app", "example.app", "1.0.0", "", "interface Counts { total: Int @computed(fn: \"countLinks\", args: {type: Edge}) }");
        assertThrows(PackLoadException.class, () -> new DomainPackLoader().loadBundle(List.of(core, app)));
        Files.writeString(app.resolve("pack.yaml"), manifest("app", "example.app", "1.0.0", "dependencies: {example.core: '>=1.0.0'}\n"));
        var bundle = new DomainPackLoader().loadBundle(List.of(core, app));
        assertEquals("Edge", bundle.ontology().schema().interfaces().getFirst().computedFields().getFirst().linkType());
    }

    @Test
    void missingAssetsSymlinkEscapesAndDuplicateYamlKeysFailBeforeBootstrap() throws Exception {
        var app = pack("app", "example.app", "1.0.0", "seed: [seed.yaml]\n", "type Item @objectType { id: ID! @primary }");
        assertThrows(PackLoadException.class, () -> new DomainPackLoader().loadBundle(List.of(app)));
        Path outside = temporary.resolve("outside.yaml");
        Files.writeString(outside, "objects: []\n");
        Files.createSymbolicLink(app.resolve("seed.yaml"), outside);
        assertThrows(PackLoadException.class, () -> new DomainPackLoader().loadBundle(List.of(app)));
        Files.delete(app.resolve("seed.yaml"));
        Files.writeString(app.resolve("seed.yaml"), "objects: []\nobjects: []\n");
        assertThrows(PackLoadException.class, () -> new DomainPackLoader().loadBundle(List.of(app)));
    }

    private Path pack(String name, String namespace, String version, String extra, String declarations) throws Exception {
        Path root = Files.createDirectory(temporary.resolve(name));
        Files.writeString(root.resolve("pack.yaml"), manifest(name, namespace, version, extra));
        Files.writeString(root.resolve("schema.odl"), header(namespace, version) + declarations);
        return root;
    }
    private String manifest(String name, String namespace, String version, String extra) {
        return "name: " + name + "\nnamespace: " + namespace + "\nversion: " + version + "\nschema: [schema.odl]\n" + extra;
    }
    private String header(String namespace, String version) {
        return "extend schema @namespace(name: \"" + namespace + "\", version: \"" + version + "\")\n";
    }
}
