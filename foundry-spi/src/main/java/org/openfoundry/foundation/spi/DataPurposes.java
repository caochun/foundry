package org.openfoundry.foundation.spi;

import java.util.Set;

/** Upstream NHS/UK-IG convenience preset; deployments may use any nonblank purpose string. */
public final class DataPurposes {
    public static final String DIRECT_CARE = "DIRECT_CARE";
    public static final String CARE_PLANNING = "CARE_PLANNING";
    public static final String SERVICE_MANAGEMENT = "SERVICE_MANAGEMENT";
    public static final String RESEARCH = "RESEARCH";
    public static final String NATIONAL_REPORTING = "NATIONAL_REPORTING";
    public static final Set<String> STANDARD = Set.of(DIRECT_CARE, CARE_PLANNING, SERVICE_MANAGEMENT, RESEARCH, NATIONAL_REPORTING);
    private DataPurposes() {}
}
