package com.laioffer.onlineorder.fulfilment;


import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;


/**
 * Runs side effects that must only happen once the surrounding transaction committed, such as counting a metric for
 * a change that could still roll back.
 */
public final class AfterCommit {

    private AfterCommit() {
    }


    /**
     * Runs {@code action} after the current transaction commits, or right away when there is no transaction.
     */
    public static void run(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
