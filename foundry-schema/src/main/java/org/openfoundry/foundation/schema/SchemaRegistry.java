package org.openfoundry.foundation.schema;

import org.openfoundry.foundation.spi.schema.OntologySchema;

import java.util.List;

public interface SchemaRegistry {
    OntologySchema current();

    OntologySchema atVersion(int version);

    List<SchemaVersion> history();

    SchemaVersion apply(OntologySchema schema, MigrationPlan migrationPlan);
}
