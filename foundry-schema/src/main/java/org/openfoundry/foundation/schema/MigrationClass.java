package org.openfoundry.foundation.schema;

public enum MigrationClass {
    SAFE,
    COMPATIBLE,
    BREAKING;

    public static MigrationClass max(MigrationClass left, MigrationClass right) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }
}
