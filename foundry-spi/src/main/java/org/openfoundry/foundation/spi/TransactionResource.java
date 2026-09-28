package org.openfoundry.foundation.spi;

/** In-memory commit participant. Holds its visibility lock until close; rollback also undoes publication. */
public interface TransactionResource extends AutoCloseable {
    void prepare();
    void publish();
    void rollback();
    @Override void close();
}
