package com.laioffer.onlineorder.statemachine;


import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;


/**
 * An immutable, validated declaration of one aggregate's lifecycle: for each trigger (a command or an event) the states
 * it fires from, the state it moves to, the kinds of actor that may fire it, its guards and when it already happened.
 * Thread-safe; declare it as a static final constant, so that a declaration error fails the class's initialization and
 * with it every test that uses the machine.
 *
 * <p>The machine only decides ({@link #decide}): it is pure and does no IO. Locking the row, the compare-and-set write
 * ({@link CompareAndSet}), history, events and audit stay with the aggregate's service, so lock order and transaction
 * boundaries are the service's.
 *
 * @param <S> the states
 * @param <T> the triggers; {@link Builder#build()} requires exactly one transition per constant
 * @param <A> the kinds of actor
 * @param <X> the context that guards and alreadyDoneWhen tests read; may be null when they read none
 */
public final class StateMachine<S extends Enum<S>, T extends Enum<T>, A extends Enum<A>, X> {

    private final String name;
    private final List<Transition<S, T, A, X>> transitions;
    private final Map<T, Transition<S, T, A, X>> transitionsByTrigger;
    private final Map<S, Set<S>> targetsByState;
    private final Set<S> initialStates;
    private final Set<S> terminalStates;


    private StateMachine(Builder<S, T, A, X> builder, Map<S, Set<S>> targets) {
        this.name = builder.name;
        this.transitions = List.copyOf(builder.transitions);
        Map<T, Transition<S, T, A, X>> byTrigger = new EnumMap<>(builder.triggerType);
        for (Transition<S, T, A, X> transition : transitions) {
            byTrigger.put(transition.trigger(), transition);
        }
        this.transitionsByTrigger = Collections.unmodifiableMap(byTrigger);
        Map<S, Set<S>> unmodifiableTargets = new EnumMap<>(builder.stateType);
        targets.forEach((state, stateTargets) ->
                unmodifiableTargets.put(state, Collections.unmodifiableSet(EnumSet.copyOf(stateTargets))));
        this.targetsByState = Collections.unmodifiableMap(unmodifiableTargets);
        this.initialStates = Collections.unmodifiableSet(EnumSet.copyOf(builder.initial));
        this.terminalStates = Collections.unmodifiableSet(EnumSet.copyOf(builder.terminal));
    }


    /**
     * @param name    used in messages and errors, for example "order"
     * @param context only fixes the context type parameter; pass {@code Void.class} when nothing reads a context
     */
    public static <S extends Enum<S>, T extends Enum<T>, A extends Enum<A>, X> Builder<S, T, A, X> builder(
            String name,
            Class<S> states,
            Class<T> triggers,
            Class<A> actors,
            Class<X> context
    ) {
        Objects.requireNonNull(context, "context");
        return new Builder<>(name, states, triggers, actors);
    }


    /**
     * Decides what {@code trigger} does to an aggregate in state {@code current}, in this order: the actor must be
     * allowed to fire it, then alreadyDoneWhen (a retry), then the from-state, then the guards in declaration order.
     * Pure: no IO.
     *
     * @throws IllegalStateException when {@code actor} may not fire {@code trigger}: a programming error, since the
     *                               endpoint's security configuration should have refused the request
     */
    public Decision<S, T> decide(S current, T trigger, A actor, X context) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(actor, "actor");
        Transition<S, T, A, X> transition = transition(trigger);
        if (!transition.actors().contains(actor)) {
            throw new IllegalStateException(name + ": " + actor + " may not fire " + trigger);
        }
        if (transition.alreadyDone() != null && transition.alreadyDone().test(current, context)) {
            return new Decision.AlreadyDone<>(trigger, current);
        }
        if (!transition.from().contains(current)) {
            return new Decision.Rejected<>(trigger, current, Decision.Reason.NOT_FROM_CURRENT_STATE, null);
        }
        for (Guard<X> guard : transition.guards()) {
            if (!guard.test().test(context)) {
                return new Decision.Rejected<>(trigger, current, Decision.Reason.GUARD_FAILED, guard.name());
            }
        }
        return new Decision.Apply<>(trigger, current, transition.stays() ? current : transition.to());
    }


    public Transition<S, T, A, X> transition(T trigger) {
        return transitionsByTrigger.get(Objects.requireNonNull(trigger, "trigger"));
    }


    /**
     * True when some transition that changes the state moves {@code from -> to}; internal ({@code stay()}) transitions
     * do not count.
     */
    public boolean canMove(S from, S to) {
        return targets(from).contains(to);
    }


    /**
     * The states some transition moves {@code from} to, without internal transitions; empty for a terminal state.
     */
    public Set<S> targets(S from) {
        return targetsByState.get(Objects.requireNonNull(from, "from"));
    }


    public Set<S> initialStates() {
        return initialStates;
    }


    /**
     * The states no transition leaves; {@link Builder#build()} checks that the declaration says the same.
     */
    public Set<S> terminalStates() {
        return terminalStates;
    }


    /**
     * In declaration order.
     */
    public List<Transition<S, T, A, X>> transitions() {
        return transitions;
    }


    public String name() {
        return name;
    }


    /**
     * The machine as a Mermaid {@code stateDiagram-v2}: the initial states, then one edge per (transition, from-state)
     * in declaration order labelled with the trigger (a self-loop for an internal transition), then the terminal
     * states. Deterministic, for docs and tests.
     */
    public String toMermaid() {
        StringBuilder diagram = new StringBuilder("stateDiagram-v2\n");
        for (S state : initialStates) {
            diagram.append("    [*] --> ").append(state.name()).append('\n');
        }
        for (Transition<S, T, A, X> transition : transitions) {
            for (S from : transition.from()) {
                S to = transition.stays() ? from : transition.to();
                diagram.append("    ").append(from.name()).append(" --> ").append(to.name())
                        .append(": ").append(transition.trigger().name()).append('\n');
            }
        }
        for (S state : terminalStates) {
            diagram.append("    ").append(state.name()).append(" --> [*]\n");
        }
        return diagram.toString();
    }


    public static final class Builder<S extends Enum<S>, T extends Enum<T>, A extends Enum<A>, X> {

        private final String name;
        private final Class<S> stateType;
        private final Class<T> triggerType;
        private final Class<A> actorType;
        private final EnumSet<S> initial;
        private final EnumSet<S> terminal;
        private final List<Transition<S, T, A, X>> transitions = new ArrayList<>();
        // Mistakes within one transition's declaration (no target, two targets); build() lists them first.
        private final List<String> declarationProblems = new ArrayList<>();


        private Builder(String name, Class<S> stateType, Class<T> triggerType, Class<A> actorType) {
            this.name = Objects.requireNonNull(name, "name");
            this.stateType = Objects.requireNonNull(stateType, "states");
            this.triggerType = Objects.requireNonNull(triggerType, "triggers");
            this.actorType = Objects.requireNonNull(actorType, "actors");
            this.initial = EnumSet.noneOf(stateType);
            this.terminal = EnumSet.noneOf(stateType);
        }


        /**
         * The states an aggregate is created in.
         */
        @SafeVarargs
        public final Builder<S, T, A, X> initial(S... states) {
            Collections.addAll(initial, states);
            return this;
        }


        /**
         * The states no transition leaves.
         */
        @SafeVarargs
        public final Builder<S, T, A, X> terminal(S... states) {
            Collections.addAll(terminal, states);
            return this;
        }


        /**
         * Starts the declaration of {@code trigger}'s transition; {@link TransitionBuilder#add()} ends it.
         */
        public TransitionBuilder<S, T, A, X> on(T trigger) {
            return new TransitionBuilder<>(this, Objects.requireNonNull(trigger, "trigger"));
        }


        /**
         * Validates the whole declaration.
         *
         * @throws IllegalStateException listing every problem found, one per line
         */
        public StateMachine<S, T, A, X> build() {
            List<String> problems = new ArrayList<>(declarationProblems);
            checkEachTriggerDeclaredOnce(problems);
            for (Transition<S, T, A, X> transition : transitions) {
                checkTransition(transition, problems);
            }
            Map<S, Set<S>> targets = targetsByState();
            for (S state : stateType.getEnumConstants()) {
                if (targets.get(state).isEmpty() && !terminal.contains(state)) {
                    problems.add(state + " has no transition to another state but is not declared terminal");
                }
            }
            if (initial.isEmpty()) {
                problems.add("no initial state is declared");
            } else {
                for (S state : EnumSet.complementOf(reachableFrom(initial, targets))) {
                    problems.add(state + " is not reachable from the initial states");
                }
            }
            Set<S> reachTerminal = statesThatReachTerminal(targets);
            for (S state : stateType.getEnumConstants()) {
                // A state without any transition out is already reported above.
                if (!targets.get(state).isEmpty() && !reachTerminal.contains(state)) {
                    problems.add(state + " cannot reach a terminal state");
                }
            }
            if (!problems.isEmpty()) {
                throw new IllegalStateException(
                        "The " + name + " state machine is invalid:\n - " + String.join("\n - ", problems)
                );
            }
            return new StateMachine<>(this, targets);
        }


        private void checkEachTriggerDeclaredOnce(List<String> problems) {
            Map<T, Integer> declarations = new EnumMap<>(triggerType);
            for (Transition<S, T, A, X> transition : transitions) {
                declarations.merge(transition.trigger(), 1, Integer::sum);
            }
            for (T trigger : triggerType.getEnumConstants()) {
                int count = declarations.getOrDefault(trigger, 0);
                if (count == 0) {
                    problems.add(trigger + " has no transition");
                } else if (count > 1) {
                    problems.add(trigger + " is declared " + count + " times");
                }
            }
        }


        private void checkTransition(Transition<S, T, A, X> transition, List<String> problems) {
            T trigger = transition.trigger();
            if (transition.actors().isEmpty()) {
                problems.add(trigger + " names no actor");
            }
            if (transition.stays()) {
                if (transition.from().isEmpty()) {
                    problems.add(trigger + " stays but declares no from state");
                }
            } else {
                if (transition.from().contains(transition.to())) {
                    problems.add(trigger + " moves " + transition.to() + " to itself; declare stay() for an internal "
                            + "transition");
                }
                if (transition.from().isEmpty() && transition.alreadyDone() == null) {
                    problems.add(trigger + " declares no from state and no alreadyDoneWhen");
                }
            }
            for (S state : transition.from()) {
                if (terminal.contains(state)) {
                    problems.add(trigger + " leaves terminal state " + state);
                }
            }
        }


        /**
         * For every state, the states the declared transitions move it to (internal transitions excluded).
         */
        private Map<S, Set<S>> targetsByState() {
            Map<S, Set<S>> targets = new EnumMap<>(stateType);
            for (S state : stateType.getEnumConstants()) {
                targets.put(state, EnumSet.noneOf(stateType));
            }
            for (Transition<S, T, A, X> transition : transitions) {
                if (transition.stays()) {
                    continue;
                }
                for (S from : transition.from()) {
                    targets.get(from).add(transition.to());
                }
            }
            return targets;
        }


        private EnumSet<S> reachableFrom(Set<S> start, Map<S, Set<S>> targets) {
            EnumSet<S> reached = EnumSet.copyOf(start);
            Deque<S> pending = new ArrayDeque<>(start);
            while (!pending.isEmpty()) {
                for (S next : targets.get(pending.pop())) {
                    if (reached.add(next)) {
                        pending.push(next);
                    }
                }
            }
            return reached;
        }


        private Set<S> statesThatReachTerminal(Map<S, Set<S>> targets) {
            EnumSet<S> reaching = EnumSet.copyOf(terminal);
            boolean grew = true;
            while (grew) {
                grew = false;
                for (S state : EnumSet.complementOf(reaching)) {
                    if (!Collections.disjoint(targets.get(state), reaching)) {
                        reaching.add(state);
                        grew = true;
                    }
                }
            }
            return reaching;
        }
    }


    public static final class TransitionBuilder<S extends Enum<S>, T extends Enum<T>, A extends Enum<A>, X> {

        private final Builder<S, T, A, X> machine;
        private final T trigger;
        private final EnumSet<S> from;
        private final EnumSet<A> actors;
        private final List<Guard<X>> guards = new ArrayList<>();
        private S to;
        private int targetDeclarations;
        private BiPredicate<S, ? super X> alreadyDone;
        private int alreadyDoneDeclarations;


        private TransitionBuilder(Builder<S, T, A, X> machine, T trigger) {
            this.machine = machine;
            this.trigger = trigger;
            this.from = EnumSet.noneOf(machine.stateType);
            this.actors = EnumSet.noneOf(machine.actorType);
        }


        /**
         * The states the trigger fires from. None for a command that is only ever a no-op (it then needs
         * {@link #alreadyDoneWhen}).
         */
        @SafeVarargs
        public final TransitionBuilder<S, T, A, X> from(S... states) {
            Collections.addAll(from, states);
            return this;
        }


        public TransitionBuilder<S, T, A, X> to(S state) {
            this.to = Objects.requireNonNull(state, "state");
            targetDeclarations++;
            return this;
        }


        /**
         * An internal transition: the command is legal from these states and keeps the state (e.g. reassign).
         */
        public TransitionBuilder<S, T, A, X> stay() {
            targetDeclarations++;
            return this;
        }


        @SafeVarargs
        public final TransitionBuilder<S, T, A, X> by(A... actors) {
            Collections.addAll(this.actors, actors);
            return this;
        }


        /**
         * Evaluated in declaration order after the from-state matched; the first failure rejects.
         */
        public TransitionBuilder<S, T, A, X> guard(String name, Predicate<? super X> test) {
            guards.add(new Guard<>(name, test));
            return this;
        }


        /**
         * The command already happened (a retry): checked BEFORE the from-state, so it also answers for states the
         * trigger does not fire from.
         */
        public TransitionBuilder<S, T, A, X> alreadyDoneWhen(BiPredicate<S, ? super X> test) {
            this.alreadyDone = Objects.requireNonNull(test, "test");
            alreadyDoneDeclarations++;
            return this;
        }


        /**
         * Ends this transition's declaration; {@link Builder#build()} reports its problems with all the others.
         */
        public Builder<S, T, A, X> add() {
            if (targetDeclarations == 0) {
                machine.declarationProblems.add(trigger + " declares neither to(...) nor stay()");
            } else if (targetDeclarations > 1) {
                machine.declarationProblems.add(trigger + " declares more than one of to(...) and stay()");
            }
            if (alreadyDoneDeclarations > 1) {
                machine.declarationProblems.add(trigger + " declares alreadyDoneWhen more than once");
            }
            machine.transitions.add(new Transition<>(trigger, from, to, actors, guards, alreadyDone));
            return machine;
        }
    }
}
