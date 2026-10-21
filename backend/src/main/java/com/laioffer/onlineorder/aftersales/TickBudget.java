package com.laioffer.onlineorder.aftersales;


import java.time.Duration;
import java.util.function.LongSupplier;


/**
 * The time a tick of the {@link AfterSalesPoller} may spend claiming work (app.after-sales.poller.tick-budget, ADR 0002
 * section 24). The deadline processor and the refund executor check it before every claim but their first, so each
 * job handles at least one item per tick, and a step or a gateway call already started is never cut short. A tick
 * therefore lasts about the budget plus one gateway timeout at most, however slow or hung the refund gateway is, and
 * the deadlines of the next tick wait no longer than that.
 */
@FunctionalInterface
interface TickBudget {

    /**
     * No time limit: a run ends at its batch size or when nothing is due (a run started outside the poller).
     */
    TickBudget UNLIMITED = () -> false;


    /**
     * @return whether the tick has used up its time, so that no further work is claimed in it
     */
    boolean spent();


    /**
     * A budget of {@code budget} from now on, measured with {@code nanoTime} (a monotonic clock such as
     * {@link System#nanoTime}).
     */
    static TickBudget startingNow(Duration budget, LongSupplier nanoTime) {
        long end = nanoTime.getAsLong() + budget.toNanos();
        // Compared as a difference, which stays right when nanoTime wraps around.
        return () -> nanoTime.getAsLong() - end >= 0;
    }
}
