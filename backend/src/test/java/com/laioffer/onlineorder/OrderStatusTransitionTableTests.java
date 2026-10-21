package com.laioffer.onlineorder;


import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.fulfilment.FulfilmentEvents;
import com.laioffer.onlineorder.model.OrderStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;


import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * The order state machine as {@link OrderStatus} declares it today: every (from, to) pair against a table written out
 * here, the 409 of an illegal move, the statuses checkout may create and the event type of each status. A state machine
 * that replaces the enum's table must keep all of it (ADR 0002, invariant I1).
 */
class OrderStatusTransitionTableTests {

    /**
     * The column (and row) order of {@link #EXPECTED_EDGES}.
     */
    private static final List<OrderStatus> STATUSES = List.of(
            OrderStatus.PLACED,
            OrderStatus.PAID,
            OrderStatus.ACCEPTED,
            OrderStatus.PREPARING,
            OrderStatus.COMPLETED,
            OrderStatus.CANCELLED
    );

    /**
     * Row = from, column = to, both in {@link #STATUSES} order. "X" is an allowed transition, "-" is not. Staying in the
     * same status is not a transition.
     */
    private static final String[][] EXPECTED_EDGES = {
            //                PLACED PAID ACCEPTED PREPARING COMPLETED CANCELLED
            /* PLACED    */ {"-",    "X", "-",     "-",      "-",      "X"},
            /* PAID      */ {"-",    "-", "X",     "-",      "-",      "X"},
            /* ACCEPTED  */ {"-",    "-", "-",     "X",      "-",      "X"},
            /* PREPARING */ {"-",    "-", "-",     "-",      "X",      "-"},
            /* COMPLETED */ {"-",    "-", "-",     "-",      "-",      "-"},
            /* CANCELLED */ {"-",    "-", "-",     "-",      "-",      "-"},
    };


    /**
     * Whether the table above allows {@code from -> to}; the tests choose their pairs from the table, not from the code.
     */
    private static boolean expectedEdge(OrderStatus from, OrderStatus to) {
        return "X".equals(EXPECTED_EDGES[STATUSES.indexOf(from)][STATUSES.indexOf(to)]);
    }


    @Test
    void theTable_shouldListEveryStatus() {
        // A new status has to be added to the table below (and to every caller's mapping) on purpose.
        Assertions.assertEquals(List.of(OrderStatus.values()), STATUSES);
        Assertions.assertEquals(STATUSES.size(), EXPECTED_EDGES.length);
        for (String[] row : EXPECTED_EDGES) {
            Assertions.assertEquals(STATUSES.size(), row.length);
        }
    }


    @Test
    void canTransitionTo_shouldMatchTheTableForAll36Pairs() {
        List<String> mismatches = new ArrayList<>();
        int pairs = 0;
        for (int from = 0; from < STATUSES.size(); from++) {
            for (int to = 0; to < STATUSES.size(); to++) {
                pairs++;
                boolean expected = "X".equals(EXPECTED_EDGES[from][to]);
                boolean actual = STATUSES.get(from).canTransitionTo(STATUSES.get(to));
                if (expected != actual) {
                    mismatches.add(STATUSES.get(from) + " -> " + STATUSES.get(to) + ": expected " + expected);
                }
            }
        }

        Assertions.assertEquals(36, pairs);
        Assertions.assertEquals(List.of(), mismatches);
    }


    @Test
    void validateTransitionTo_shouldAcceptTheSameStatusAndEveryEdge() {
        for (OrderStatus from : STATUSES) {
            // Staying put is always allowed, also in the terminal statuses: callers treat it as a no-op.
            Assertions.assertDoesNotThrow(() -> from.validateTransitionTo(from), from.name());
            for (OrderStatus to : STATUSES) {
                if (expectedEdge(from, to)) {
                    Assertions.assertDoesNotThrow(() -> from.validateTransitionTo(to), from + " -> " + to);
                }
            }
        }
    }


    @Test
    void validateTransitionTo_shouldRefuseEveryOtherPairWithTheExactMessage() {
        int refused = 0;
        for (OrderStatus from : STATUSES) {
            for (OrderStatus to : STATUSES) {
                if (from == to || expectedEdge(from, to)) {
                    continue;
                }
                ConflictException conflict = Assertions.assertThrows(
                        ConflictException.class,
                        () -> from.validateTransitionTo(to),
                        from + " -> " + to
                );
                Assertions.assertEquals(ConflictException.DEFAULT_CODE, conflict.getCode());
                Assertions.assertEquals("Invalid order status transition: " + from + " -> " + to, conflict.getMessage());
                refused++;
            }
        }
        // 36 pairs: 6 same-status pairs and 7 edges are allowed.
        Assertions.assertEquals(23, refused);
    }


    @Test
    void edges_shouldNeverLeadIntoPlacedAndOnlyFromPlacedIntoPaid() {
        for (OrderStatus from : STATUSES) {
            Assertions.assertFalse(from.canTransitionTo(OrderStatus.PLACED), from + " -> PLACED");
            Assertions.assertEquals(from == OrderStatus.PLACED, from.canTransitionTo(OrderStatus.PAID), from + " -> PAID");
        }
    }


    @Test
    void completedAndCancelled_shouldBeTerminal() {
        for (OrderStatus terminal : List.of(OrderStatus.COMPLETED, OrderStatus.CANCELLED)) {
            for (OrderStatus to : STATUSES) {
                Assertions.assertFalse(terminal.canTransitionTo(to), terminal + " -> " + to);
            }
        }
        // Every other status still has a way out.
        for (OrderStatus open : List.of(OrderStatus.PLACED, OrderStatus.PAID, OrderStatus.ACCEPTED, OrderStatus.PREPARING)) {
            Assertions.assertTrue(STATUSES.stream().anyMatch(open::canTransitionTo), open.name());
        }
    }


    @Test
    void normalizeForCheckout_shouldOnlyCreatePlacedOrPaidOrders() {
        Set<OrderStatus> creatable = EnumSet.noneOf(OrderStatus.class);
        for (OrderStatus status : STATUSES) {
            try {
                creatable.add(OrderStatus.normalizeForCheckout(status.name()));
            } catch (BadRequestException ex) {
                Assertions.assertEquals(BadRequestException.DEFAULT_CODE, ex.getCode());
                Assertions.assertEquals("Checkout can only create PLACED or PAID orders", ex.getMessage());
            }
        }

        Assertions.assertEquals(EnumSet.of(OrderStatus.PLACED, OrderStatus.PAID), creatable);
    }


    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "null", textBlock = """
            null      | PLACED
            ''        | PLACED
            '   '     | PLACED
            placed    | PLACED
            ' paid '  | PAID
            Paid      | PAID
            """)
    void normalizeForCheckout_shouldDefaultToPlacedAndIgnoreCaseAndBlanks(String requested, OrderStatus expected) {
        Assertions.assertEquals(expected, OrderStatus.normalizeForCheckout(requested));
    }


    @ParameterizedTest
    @ValueSource(strings = {"SHIPPED", "refunded"})
    void normalizeForCheckout_shouldRefuseAnUnknownStatusWithItsName(String requested) {
        BadRequestException badRequest = Assertions.assertThrows(
                BadRequestException.class,
                () -> OrderStatus.normalizeForCheckout(requested)
        );

        Assertions.assertEquals("Unsupported order status: " + requested, badRequest.getMessage());
    }


    @Test
    void eventType_shouldBeTheFulfilmentEventOfEachStatus() {
        Map<OrderStatus, String> expected = Map.of(
                OrderStatus.PLACED, FulfilmentEvents.ORDER_CREATED,
                OrderStatus.PAID, FulfilmentEvents.ORDER_PAID,
                OrderStatus.ACCEPTED, FulfilmentEvents.ORDER_ACCEPTED,
                OrderStatus.PREPARING, FulfilmentEvents.ORDER_PREPARING,
                OrderStatus.COMPLETED, FulfilmentEvents.ORDER_COMPLETED,
                OrderStatus.CANCELLED, FulfilmentEvents.ORDER_CANCELLED
        );

        for (OrderStatus status : STATUSES) {
            Assertions.assertEquals(expected.get(status), status.eventType(), status.name());
        }
        Assertions.assertEquals(
                List.of("order.created", "order.paid", "order.accepted", "order.preparing", "order.completed", "order.cancelled"),
                STATUSES.stream().map(OrderStatus::eventType).toList()
        );
    }
}
