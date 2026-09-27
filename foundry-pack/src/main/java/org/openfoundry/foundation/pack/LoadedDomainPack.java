package org.openfoundry.foundation.pack;

import org.openfoundry.foundation.actions.ActionManifest;
import org.openfoundry.foundation.schema.CompiledOntology;

import java.nio.file.Path;
import java.util.Map;

public record LoadedDomainPack(PackManifest manifest, Path directory,
                               CompiledOntology ontology,
                               Map<String, ActionManifest> actions, PackAssets assets) {
    public LoadedDomainPack(PackManifest manifest, Path directory, CompiledOntology ontology, Map<String, ActionManifest> actions) {
        this(manifest, directory, ontology, actions, PackAssets.empty());
    }

    public LoadedDomainPack {
        actions = Map.copyOf(actions);
    }
}
