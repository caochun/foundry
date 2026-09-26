package org.openfoundry.foundation.storage.jdbc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseDialectTest {
    @Test
    void exposesDomesticDialectTargetsWithoutVendorExtensions() {
        assertTrue(DatabaseDialect.openGauss().currentTablesDdl().contains("of_objects"));
        assertTrue(DatabaseDialect.kingbase().currentTablesDdl().contains("of_object_history"));
        assertTrue(DatabaseDialect.dameng().currentTablesDdl().contains("of_outbox_events"));
    }
}
