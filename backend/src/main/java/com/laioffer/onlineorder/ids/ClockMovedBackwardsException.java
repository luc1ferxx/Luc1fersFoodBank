package com.laioffer.onlineorder.ids;


/**
 * The wall clock is behind the timestamp of the last id by more than app.ids.max-clock-backward. The generator refuses
 * instead of waiting for minutes or reusing a timestamp; it issues ids again once the clock has caught up.
 */
public class ClockMovedBackwardsException extends IdGeneratorUnavailableException {

    private static final long serialVersionUID = 1L;


    public ClockMovedBackwardsException(int workerId, long behindMillis, long toleranceMillis) {
        super("The clock of Snowflake worker " + workerId + " is " + behindMillis
                + " ms behind its last id (tolerance " + toleranceMillis + " ms)");
    }
}
