package org.openfoundry.foundation.storage.jdbc;

import javax.sql.DataSource;
import java.sql.Connection;

/** Trusted metadata adapters may share this connection; they must never commit or close it. */
public interface JdbcTransactionAccess {
    DataSource transactionDataSource();
    Connection transactionConnection();
}
