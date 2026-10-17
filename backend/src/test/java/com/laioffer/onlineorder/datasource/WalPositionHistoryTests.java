package com.laioffer.onlineorder.datasource;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


class WalPositionHistoryTests {

    private static final long SECOND = 1_000_000_000L;


    @Test
    void noLagIsKnownBeforeThePrimarysPositionWasRecorded() {
        Assertions.assertNull(new WalPositionHistory(10).lag(100, 0));
    }


    @Test
    void aReplicaAtOrPastTheNewestPositionIsCaughtUp() {
        WalPositionHistory history = new WalPositionHistory(10);
        history.record(0, 100);
        history.record(SECOND, 200);

        Assertions.assertEquals(new WalPositionHistory.Lag(0.0, true), history.lag(200, 5 * SECOND));
        Assertions.assertEquals(new WalPositionHistory.Lag(0.0, true), history.lag(250, 5 * SECOND));
    }


    @Test
    void aReplicaBehindIsAsOldAsTheFirstPositionItIsMissing() {
        WalPositionHistory history = new WalPositionHistory(10);
        history.record(0, 100);
        history.record(SECOND, 200);
        history.record(2 * SECOND, 300);

        // Has 200 but not 300: missing what the primary had at 2s.
        Assertions.assertEquals(new WalPositionHistory.Lag(0.5, true), history.lag(250, 2 * SECOND + SECOND / 2));
        // Has 100 but not 200 (nor 300): missing what the primary had at 1s.
        Assertions.assertEquals(new WalPositionHistory.Lag(4.0, true), history.lag(150, 5 * SECOND));
    }


    @Test
    void aReplicaBehindEveryRememberedPositionHasNoUpperBound() {
        WalPositionHistory history = new WalPositionHistory(10);
        history.record(SECOND, 100);
        history.record(2 * SECOND, 200);

        WalPositionHistory.Lag lag = history.lag(50, 3 * SECOND);

        Assertions.assertFalse(lag.bounded());
        Assertions.assertEquals(2.0, lag.seconds());
    }


    @Test
    void anUnchangedPositionKeepsTheTimeItWasFirstSeen() {
        WalPositionHistory history = new WalPositionHistory(10);
        history.record(0, 100);
        history.record(SECOND, 200);
        // An idle primary: the position stays at 200.
        history.record(5 * SECOND, 200);
        history.record(9 * SECOND, 200);

        // A replica that stopped replaying at 100 has been missing 200 since 1s, not since 9s.
        Assertions.assertEquals(new WalPositionHistory.Lag(9.0, true), history.lag(100, 10 * SECOND));
    }


    @Test
    void theOldestPositionsAreForgottenBeyondTheCapacity() {
        WalPositionHistory history = new WalPositionHistory(2);
        history.record(0, 100);
        history.record(SECOND, 200);
        history.record(2 * SECOND, 300);

        // 100 was dropped: a replica at 150 is behind everything remembered.
        Assertions.assertFalse(history.lag(150, 3 * SECOND).bounded());
        Assertions.assertTrue(history.lag(200, 3 * SECOND).bounded());
    }


    @Test
    void aPrimaryPositionGoingBackwardsStartsTheHistoryOver() {
        WalPositionHistory history = new WalPositionHistory(10);
        history.record(0, 1_000);
        history.record(SECOND, 2_000);
        // The database behind the URL was replaced.
        history.record(2 * SECOND, 50);

        Assertions.assertEquals(new WalPositionHistory.Lag(0.0, true), history.lag(50, 3 * SECOND));
        Assertions.assertFalse(history.lag(40, 3 * SECOND).bounded());
    }


    @Test
    void aClockThatSeemsToGoBackReportsNoNegativeLag() {
        WalPositionHistory history = new WalPositionHistory(1);
        history.record(5 * SECOND, 100);
        history.record(6 * SECOND, 200);

        Assertions.assertEquals(0.0, history.lag(150, 4 * SECOND).seconds());
    }
}
