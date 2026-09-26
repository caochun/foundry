package org.openfoundry.foundation.api;

import org.openfoundry.foundation.security.SecurityPrincipal;
import org.openfoundry.foundation.spi.RequestContext;

public record ApiRequestContext(RequestContext request, SecurityPrincipal principal) {}
