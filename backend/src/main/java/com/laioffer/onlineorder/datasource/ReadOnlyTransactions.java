package com.laioffer.onlineorder.datasource;


import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;


import java.util.function.Supplier;


/**
 * The programmatic form of {@code @Transactional(readOnly = true)}, for a read that cannot be a method of its own on a
 * proxied bean: the part of a request after a check that must read the primary (a merchant's or rider's active flag,
 * so a deactivation takes effect on the next request whatever the replica lag), or the database load inside a
 * {@code @Cacheable} method (so a cache hit never opens a transaction, which without the lazy routing proxy would take
 * a pooled connection per hit). With replicas configured the block reads a replica; without, the primary, as before.
 *
 * <p>The block must only read: on PostgreSQL a write or {@code SELECT ... FOR UPDATE} in it fails (read-only
 * transaction, or a hot standby).
 */
@Component
public class ReadOnlyTransactions {

    private final PlatformTransactionManager transactionManager;


    public ReadOnlyTransactions(PlatformTransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }


    /**
     * @param name the transaction name, conventionally {@code Class.method} of the caller
     */
    public <T> T read(String name, Supplier<T> work) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        template.setName(name);
        return template.execute(status -> work.get());
    }
}
