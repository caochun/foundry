package org.openfoundry.foundation.pack;

import org.openfoundry.foundation.actions.ActionManifest;
import org.openfoundry.foundation.schema.CompiledOntology;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** One closed ontology and asset set compiled from a validated dependency graph. */
public record LoadedPackBundle(List<PackManifest> manifests, CompiledOntology ontology, Map<String, String> typeOwners,
                               Map<String, ActionManifest> actions, PackAssets assets, Set<String> capabilities, String digest) {
    public LoadedPackBundle {
        manifests = List.copyOf(manifests);
        typeOwners = Map.copyOf(typeOwners);
        actions = Map.copyOf(actions);
        capabilities = Set.copyOf(capabilities);
    }
}
