package com.laioffer.onlineorder.statemachine;


/**
 * Raised when a compare-and-set write did not change exactly one row: the row lock contract was broken. The caller
 * read the state under {@code SELECT ... FOR UPDATE}, so with the lock held this never happens; it is a defence that
 * turns a silent lost update into a rolled-back transaction (a 500). Fix the path that wrote without the lock instead
 * of retrying.
 */
public final class StaleTransitionException extends IllegalStateException {

    public StaleTransitionException(String message) {
        super(message);
    }
}
