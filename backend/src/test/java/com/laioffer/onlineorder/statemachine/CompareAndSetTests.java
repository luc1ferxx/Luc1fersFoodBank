package com.laioffer.onlineorder.statemachine;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;


class CompareAndSetTests {

    @Test
    void expectOneRow_shouldAcceptExactlyOneRow() {
        Assertions.assertDoesNotThrow(() -> CompareAndSet.expectOneRow(1, "order", 5L, "PAID"));
    }


    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void expectOneRow_shouldRejectAnyOtherCount(int rows) {
        StaleTransitionException stale = Assertions.assertThrows(
                StaleTransitionException.class,
                () -> CompareAndSet.expectOneRow(rows, "order", 5L, "PAID")
        );

        Assertions.assertEquals(
                "Compare-and-set of order 5 from PAID changed " + rows + " rows instead of 1",
                stale.getMessage()
        );
        // An IllegalStateException: the transaction rolls back and the request fails with 500.
        Assertions.assertInstanceOf(IllegalStateException.class, stale);
    }
}
