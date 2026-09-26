package org.openfoundry.foundation.pack;

import org.openfoundry.foundation.actions.ActionManifest;
import org.openfoundry.foundation.schema.CompiledOntology;

import java.nio.file.Path;
import java.util.Map;

public record LoadedDomainPack(PackManifest manifest, Path directory,
                               CompiledOntology ontology,
                               Map<String, ActionManifest> actions) {
    public LoadedDomainPack {
        actions = Map.copyOf(actions);
    }
}
