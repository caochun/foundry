package org.openfoundry.foundation.actions;

import org.openfoundry.foundation.spi.LinkRecord;
import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.schema.LinkFieldDefinition;

import java.util.List;

/** Concrete relationship read, independently authorized from any later mutation. */
public record ActionNavigationRead(ObjectRecord source, LinkFieldDefinition field,
                                   List<LinkRecord> links, List<ObjectRecord> targets) {
    public ActionNavigationRead {
        links = List.copyOf(links);
        targets = List.copyOf(targets);
    }
}
