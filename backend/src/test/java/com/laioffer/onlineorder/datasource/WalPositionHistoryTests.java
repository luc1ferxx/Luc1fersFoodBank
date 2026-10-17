package com.laioffer.onlineorder.datasource;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


class WalPositionHistoryTests {

    private static final long SECOND = 1_000_000_000L;


    @Test
    void nothingIsKnownBeforeThePrimarysPositionWasRecorded() {
        Assertions.assertNull(new WalPositionHistory(10).freshness(100));
    }


    @Test
    void aReplicaAtOrPastTheNewestPositionHasEverythingCommittedBeforeTheNewestSample() {
        WalPositionHistory history = new WalPositionHistory(10);
        history.record(0, 100);
        history.record(SECOND, 200);

        Assertions.assertEquals(new WalPositionHistory.Freshness(SECOND, true), history.freshness(200));
        Assertions.assertEquals(new WalPositionHistory.Freshness(SECOND, true), history.freshness(250));
        Assertions.assertEquals(4.0, history.freshness(200).ageSeconds(5 * SECOND));
    }


    @Test
    void aReplicaBehindIsAsFreshAsTheNewestSampleItHasReplayed() {
        WalPositionHistory history = new WalPositionHistory(10);
        history.record(0, 100);
        history.record(SECOND, 200);
        history.record(2 * SECOND, 300);

        // Has 200 but not 300: it has everything committed before 1s.
        Assertions.assertEquals(new WalPositionHistory.Freshness(SECOND, true), history.freshness(250));
        // Has 100 but not 200: everything committed before 0s.
        Assertions.assertEquals(new WalPositionHistory.Freshness(0, true), history.freshness(150));
        Assertions.assertEquals(5.0, history.freshness(150).ageSeconds(5 * SECOND));
    }


    @Test
    void aReplicaBehindEveryRememberedPositionIsOlderThanTheOldestSample() {
        WalPositionHistory history = new WalPositionHistory(10);
        history.record(SECOND, 100);
        history.record(2 * SECOND, 200);

        WalPositionHistory.Freshness freshness = history.freshness(50);

        Assertions.assertFalse(freshness.bounded());
        Assertions.assertEquals(SECOND, freshness.asOfNanos());
        Assertions.assertEquals(2.0, freshness.ageSeconds(3 * SECOND), "a lower bound");
    }


    @Test
    void anUnchangedPositionMovesTheTimeItWasLastSeen() {
        WalPositionHistory history = new WalPositionHistory(10);
        history.record(0, 100);
        history.record(SECOND, 200);
        // An idle primary: the position stays at 200.
        history.record(5 * SECOND, 200);
        history.record(9 * SECOND, 200);

        // A caught-up replica has everything committed before the last time the primary was still at 200.
        Assertions.assertEquals(new WalPositionHistory.Freshness(9 * SECOND, true), history.freshness(200));
        // A replica that stopped at 100 has been missing 200 since 1s; it has everything up to 0s.
        Assertions.assertEquals(new WalPositionHistory.Freshness(0, true), history.freshness(100));
        history.record(10 * SECOND, 300);
        Assertions.assertEquals(new WalPositionHistory.Freshness(9 * SECOND, true), history.freshness(299));
    }


    @Test
    void theOldestPositionsAreForgottenBeyondTheCapacity() {
        WalPositionHistory history = new WalPositionHistory(2);
        history.record(0, 100);
        history.record(SECOND, 200);
        history.record(2 * SECOND, 300);

        // 100 was dropped: a replica at 150 is behind everything remembered.
        Assertions.assertFalse(history.freshness(150).bounded());
        Assertions.assertTrue(history.freshness(200).bounded());
    }


    @Test
    void aPrimaryPositionGoingBackwardsStartsTheHistoryOver() {
        WalPositionHistory history = new WalPositionHistory(10);
        history.record(0, 1_000);
        history.record(SECOND, 2_000);
        // The database behind the URL was replaced.
        history.record(2 * SECOND, 50);

        Assertions.assertEquals(new WalPositionHistory.Freshness(2 * SECOND, true), history.freshness(50));
        Assertions.assertFalse(history.freshness(40).bounded());
    }


    @Test
    void aClockThatSeemsToGoBackReportsNoNegativeAge() {
        WalPositionHistory history = new WalPositionHistory(1);
        history.record(5 * SECOND, 100);

        Assertions.assertEquals(0.0, history.freshness(100).ageSeconds(4 * SECOND));
    }
}
