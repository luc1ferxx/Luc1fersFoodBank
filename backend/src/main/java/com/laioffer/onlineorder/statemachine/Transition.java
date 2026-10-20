package com.laioffer.onlineorder.statemachine;


import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;


/**
 * What one trigger does, as declared with {@link StateMachine.TransitionBuilder}. The collections are unmodifiable
 * copies; {@link StateMachine.Builder#build()} checks the declaration as a whole.
 *
 * @param trigger     the command or event
 * @param from        the states the trigger may fire from; empty for a command that can only ever be a no-op
 * @param to          the state it moves to; null for an internal transition that keeps the state ({@link #stays()})
 * @param actors      the kinds of actor that may fire it
 * @param guards      checked in this order once the from-state matched
 * @param alreadyDone true when the command already happened (a retry); checked before the from-state. Null when the
 *                    trigger has no such check
 */
public record Transition<S, T, A, X>(
        T trigger,
        Set<S> from,
        S to,
        Set<A> actors,
        List<Guard<X>> guards,
        BiPredicate<S, ? super X> alreadyDone
) {

    public Transition {
        Objects.requireNonNull(trigger, "trigger");
        from = Collections.unmodifiableSet(new LinkedHashSet<>(Objects.requireNonNull(from, "from")));
        actors = Collections.unmodifiableSet(new LinkedHashSet<>(Objects.requireNonNull(actors, "actors")));
        guards = List.copyOf(Objects.requireNonNull(guards, "guards"));
    }


    /**
     * True for an internal transition: the command is legal from the {@link #from} states and keeps the state.
     */
    public boolean stays() {
        return to == null;
    }
}
