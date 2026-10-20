package com.laioffer.onlineorder.statemachine;


import java.util.Objects;
import java.util.function.Predicate;


/**
 * A named condition a transition must pass after its from-state matched; {@link Decision.Rejected#guard()} names the
 * first one that failed.
 *
 * @param name reported when the test fails, for example "within the response window"
 * @param test reads the context the caller passes to {@link StateMachine#decide}
 */
public record Guard<X>(String name, Predicate<? super X> test) {

    public Guard {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(test, "test");
    }
}
