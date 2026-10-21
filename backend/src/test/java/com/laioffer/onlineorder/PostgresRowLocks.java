package com.laioffer.onlineorder;


import org.junit.jupiter.api.Assertions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;


import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;


/**
 * Row-lock helpers for race tests on PostgreSQL: wait until backends are blocked on a lock, and queue commands on one
 * row lock in a chosen order.
 *
 * <p>PostgreSQL grants a row lock to its waiters in the order they queued: a waiter first takes the tuple lock, which
 * puts it behind the waiters before it. Commands queued behind {@link #holdRowLock} therefore lock the row one after
 * the other, in the order they were queued, once the holder commits, and each one decides on the row as the command
 * before it committed it. A race test can so run every serial order of two commands on every run, instead of hoping
 * that thread timing produces each of them, and it proves that the later command waited for the row (ADR 0002 I7).
 *
 * <p>The lock waits are counted over the whole database, so these helpers are only meaningful while nothing else uses
 * it (each PostgreSQL test class runs its own container, and the tests switch off the background jobs).
 */
public final class PostgresRowLocks {

    private static final long WAIT_SECONDS = 30;

    private PostgresRowLocks() {
    }


    /**
     * Waits until at least {@code waiters} backends of this database are blocked on a lock (wait_event_type 'Lock'),
     * for example an UPDATE or a SELECT ... FOR UPDATE behind a row another open transaction holds.
     */
    public static void awaitLockWaiters(JdbcTemplate jdbcTemplate, int waiters, String failureMessage)
            throws InterruptedException {
        awaitLockWaiters(jdbcTemplate, waiters, null, failureMessage);
    }


    /**
     * Locks row {@code id} of {@code table} with SELECT ... FOR UPDATE in a transaction of its own, on a thread of
     * {@code executor} that stays busy until the holder is released, and returns once the row is held. Commands are
     * then queued with {@link RowLockHolder#queue} and run once {@link RowLockHolder#release} commits the holder.
     * The executor needs a thread for the holder and one for every queued command.
     */
    public static RowLockHolder holdRowLock(
            ExecutorService executor,
            PlatformTransactionManager transactionManager,
            JdbcTemplate jdbcTemplate,
            String table,
            Long id
    ) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Object> holder = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
            jdbcTemplate.queryForObject("SELECT id FROM " + table + " WHERE id = ? FOR UPDATE", Long.class, id);
            held.countDown();
            try {
                Assertions.assertTrue(release.await(WAIT_SECONDS, TimeUnit.SECONDS), "The row lock was never released");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
            return null;
        }));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!held.await(20, TimeUnit.MILLISECONDS)) {
            if (holder.isDone()) {
                holder.get();
            }
            if (System.nanoTime() > deadline) {
                Assertions.fail("The " + table + " row " + id + " was never locked");
            }
        }
        return new RowLockHolder(executor, jdbcTemplate, holder, release, table + " row " + id);
    }


    private static void awaitLockWaiters(JdbcTemplate jdbcTemplate, int waiters, Future<?> command, String failureMessage)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM pg_stat_activity
                    WHERE datname = current_database()
                      AND wait_event_type = 'Lock'
                    """, Integer.class);
            if (waiting != null && waiting >= waiters) {
                return;
            }
            if (command != null && command.isDone()) {
                Assertions.fail(failureMessage + ": it finished without waiting (" + outcome(command) + ")");
            }
            Thread.sleep(20);
        }
        Assertions.fail(failureMessage);
    }


    private static String outcome(Future<?> command) throws InterruptedException {
        try {
            return "returned " + command.get();
        } catch (ExecutionException ex) {
            return "threw " + ex.getCause();
        }
    }


    /**
     * A transaction holding one row FOR UPDATE, with the commands queued behind it.
     */
    public static final class RowLockHolder implements AutoCloseable {

        private final ExecutorService executor;
        private final JdbcTemplate jdbcTemplate;
        private final Future<Object> holder;
        private final CountDownLatch release;
        private final String row;
        private int queued;


        private RowLockHolder(
                ExecutorService executor,
                JdbcTemplate jdbcTemplate,
                Future<Object> holder,
                CountDownLatch release,
                String row
        ) {
            this.executor = executor;
            this.jdbcTemplate = jdbcTemplate;
            this.holder = holder;
            this.release = release;
            this.row = row;
        }


        /**
         * Submits {@code command} and returns once it waits on a lock, which is the held row (or, behind earlier
         * queued commands, the row's tuple lock): it locks the row after every command queued before it.
         */
        public <T> Future<T> queue(Callable<T> command) throws InterruptedException {
            Future<T> future = executor.submit(command);
            queued++;
            awaitLockWaiters(jdbcTemplate, queued, future, "Command " + queued + " never queued for the " + row);
            return future;
        }


        /**
         * Commits the holder, so that the queued commands lock the row in turn, and waits for the commit.
         */
        public void release() throws Exception {
            release.countDown();
            holder.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }


        @Override
        public void close() {
            release.countDown();
        }
    }
}
