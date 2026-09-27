package org.openfoundry.foundation.actions;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FilteredDeletionManifestTest {
    private static final String PREFIX = "action: Unlink\nversion: 1\neffects:\n  - type: deleteLink\n    linkType: Related\n";

    @Test
    void parsesUpstreamSelectionShapeAndDefaultsToOne() {
        var manifest = new ActionManifestParser().parse(PREFIX + "    filter: {from: source, to: target, active: true}\n");
        var effect = (ActionManifest.DeleteLink) manifest.effects().getFirst();
        assertNull(effect.linkId());
        assertEquals(new ActionManifest.LinkFilter("source", "target", true), effect.filter());
        assertEquals(ActionManifest.LinkExpectation.ONE, effect.expect());
        var all = new ActionManifestParser().parse(PREFIX + "    filter: {to: target}\n    expect: ALL\n");
        assertEquals(ActionManifest.LinkExpectation.ALL, ((ActionManifest.DeleteLink) all.effects().getFirst()).expect());
    }

    @Test
    void rejectsAmbiguousAndMalformedSelectors() {
        for (String suffix : List.of("", "    linkId: edge\n    filter: {}\n", "    filter: {active: yesPlease}\n",
                "    filter: {fro: source}\n", "    filter: {from: 12}\n", "    filter: {}\n    expect: SOME\n",
                "    linkId: edge\n    expect: ALL\n", "    filter: invalid\n")) {
            assertThrows(ActionParseException.class, () -> new ActionManifestParser().parse(PREFIX + suffix));
        }
    }

    @Test
    void directIdDeclarationsKeepTheirExistingPersistentFingerprint() {
        assertEquals("36d48a2b8a871f4e4c4a587b6882eaa54963f765f2dea7ff6aac23f05596ce1a",
                ActionFingerprint.hash(new ActionManifest.DeleteLink("Related", "params.linkId")));
        assertEquals("c23c19f1b342f09ccb15d1ef970cac4793e4a8e17ef58a2039b75e64b578b28d",
                ActionFingerprint.hash(new ActionManifest("Unlink", 1, false, List.of(),
                        List.of(new ActionManifest.DeleteLink("Related", "params.linkId")))));
        // Captured from the prior six-field manifest implementation at 6e2e400.
        assertEquals("2f66830a72c87aeef383be3174827f47ecfb422c4364eda975a972bf62d2dd1f",
                ActionFingerprint.hash(new ActionManifest("Return", 1, false, List.of(), List.of(), ActionManifest.RollbackPolicy.ROLLBACK_ALL)));
        var one = new ActionManifest.DeleteLink("Related", new ActionManifest.LinkFilter("source", null, true), ActionManifest.LinkExpectation.ONE);
        var all = new ActionManifest.DeleteLink("Related", one.filter(), ActionManifest.LinkExpectation.ALL);
        assertNotEquals(ActionFingerprint.hash(one), ActionFingerprint.hash(all));
    }

    @Test
    void valuesResolveNowNestedParametersQuotedLiteralsAndNullWithoutMutatingInputs() {
        var actor = new ActionActor("operator", Set.of("admin"));
        var now = Instant.parse("2030-01-01T00:00:00Z");
        var values = new ActionValues(Map.of("details", Map.of("name", "nested")), actor, now);
        assertEquals(now.toString(), values.value("now"));
        assertEquals("operator", values.value("actor.id"));
        assertEquals("nested", values.value("params.details.name"));
        assertNull(values.value("params.missing"));
        assertEquals("now", values.value("'now'"));
        assertEquals("ON_LOAN", values.value("ON_LOAN"));
        assertTrue(new CelExpressionEvaluator().evaluate("now == timestamp('2030-01-01T00:00:00Z')", Map.of(), actor, now));
    }
}
