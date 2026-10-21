package com.laioffer.onlineorder.statemachine;


import java.util.Objects;


/**
 * What {@link StateMachine#decide} decided. Callers tell the cases apart with {@code instanceof} patterns (switch
 * pattern matching is still a preview feature in Java 17) and map a {@link Rejected} to their own error.
 */
public sealed interface Decision<S, T> permits Decision.Apply, Decision.AlreadyDone, Decision.Rejected {

    T trigger();


    /**
     * Write the change: move from {@code from} to {@code to}, which equals {@code from} for an internal transition.
     */
    record Apply<S, T>(T trigger, S from, S to) implements Decision<S, T> {

        public Apply {
            Objects.requireNonNull(trigger, "trigger");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }
    }


    /**
     * The command already happened (a retry): nothing to write.
     */
    record AlreadyDone<S, T>(T trigger, S current) implements Decision<S, T> {

        public AlreadyDone {
            Objects.requireNonNull(trigger, "trigger");
            Objects.requireNonNull(current, "current");
        }
    }


    /**
     * The trigger may not fire now.
     *
     * @param guard the name of the guard that failed for {@link Reason#GUARD_FAILED}; null otherwise
     */
    record Rejected<S, T>(T trigger, S current, Reason reason, String guard) implements Decision<S, T> {

        public Rejected {
            Objects.requireNonNull(trigger, "trigger");
            Objects.requireNonNull(current, "current");
            Objects.requireNonNull(reason, "reason");
            if ((reason == Reason.GUARD_FAILED) != (guard != null)) {
                throw new IllegalArgumentException("A guard is named exactly when the reason is GUARD_FAILED");
            }
        }
    }


    enum Reason {
        /**
         * The current state is not one the trigger fires from.
         */
        NOT_FROM_CURRENT_STATE,
        /**
         * The from-state matched but a guard failed.
         */
        GUARD_FAILED
    }
}
