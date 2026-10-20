package com.laioffer.onlineorder.fulfilment.dispatch;


import com.laioffer.onlineorder.fulfilment.FulfilmentEvents;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;


/**
 * The delivery state machine as {@link DeliveryStatus} declares it today: every (from, to) pair against a table written
 * out here, which statuses hold a rider (and so count towards the rider's load) and the event type of each status. A
 * state machine that replaces the enum's table must keep all of it (ADR 0002, invariants I16 and I20).
 */
class DeliveryStatusTransitionTableTests {

    /**
     * The column (and row) order of {@link #EXPECTED_EDGES}.
     */
    private static final List<DeliveryStatus> STATUSES = List.of(
            DeliveryStatus.ASSIGNED,
            DeliveryStatus.PICKED_UP,
            DeliveryStatus.DELIVERED,
            DeliveryStatus.CANCELLED
    );

    /**
     * Row = from, column = to, both in {@link #STATUSES} order. "X" is an allowed transition, "-" is not. Staying in the
     * same status is not a transition.
     */
    private static final String[][] EXPECTED_EDGES = {
            //                ASSIGNED PICKED_UP DELIVERED CANCELLED
            /* ASSIGNED  */ {"-",      "X",      "-",      "X"},
            /* PICKED_UP */ {"-",      "-",      "X",      "X"},
            /* DELIVERED */ {"-",      "-",      "-",      "-"},
            /* CANCELLED */ {"-",      "-",      "-",      "-"},
    };


    @Test
    void theTable_shouldListEveryStatus() {
        // A new status has to be added to the table below (and to every caller's mapping) on purpose.
        Assertions.assertEquals(List.of(DeliveryStatus.values()), STATUSES);
        Assertions.assertEquals(STATUSES.size(), EXPECTED_EDGES.length);
        for (String[] row : EXPECTED_EDGES) {
            Assertions.assertEquals(STATUSES.size(), row.length);
        }
    }


    @Test
    void canTransitionTo_shouldMatchTheTableForAll16Pairs() {
        List<String> mismatches = new ArrayList<>();
        int pairs = 0;
        int edges = 0;
        for (int from = 0; from < STATUSES.size(); from++) {
            for (int to = 0; to < STATUSES.size(); to++) {
                pairs++;
                boolean expected = "X".equals(EXPECTED_EDGES[from][to]);
                boolean actual = STATUSES.get(from).canTransitionTo(STATUSES.get(to));
                if (actual) {
                    edges++;
                }
                if (expected != actual) {
                    mismatches.add(STATUSES.get(from) + " -> " + STATUSES.get(to) + ": expected " + expected);
                }
            }
        }

        Assertions.assertEquals(16, pairs);
        Assertions.assertEquals(List.of(), mismatches);
        Assertions.assertEquals(4, edges);
    }


    @Test
    void canTransitionTo_shouldNeverStayInTheSameStatus() {
        // A repeat is not an edge: DispatchService answers it with the delivery unchanged before it asks the table.
        for (DeliveryStatus status : STATUSES) {
            Assertions.assertFalse(status.canTransitionTo(status), status.name());
        }
    }


    @Test
    void edges_shouldNeverLeadIntoAssigned() {
        // A delivery is only ever created ASSIGNED (insertAssignedIfAbsent) and never returns there.
        for (DeliveryStatus from : STATUSES) {
            Assertions.assertFalse(from.canTransitionTo(DeliveryStatus.ASSIGNED), from + " -> ASSIGNED");
        }
    }


    @Test
    void deliveredAndCancelled_shouldBeTerminal() {
        for (DeliveryStatus terminal : List.of(DeliveryStatus.DELIVERED, DeliveryStatus.CANCELLED)) {
            for (DeliveryStatus to : STATUSES) {
                Assertions.assertFalse(terminal.canTransitionTo(to), terminal + " -> " + to);
            }
        }
        // A rider must pick an order up before delivering it.
        Assertions.assertFalse(DeliveryStatus.ASSIGNED.canTransitionTo(DeliveryStatus.DELIVERED));
    }


    @Test
    void holdsRider_shouldBeTrueOnlyWhileTheDeliveryIsOpen() {
        Map<DeliveryStatus, Boolean> expected = Map.of(
                DeliveryStatus.ASSIGNED, true,
                DeliveryStatus.PICKED_UP, true,
                DeliveryStatus.DELIVERED, false,
                DeliveryStatus.CANCELLED, false
        );

        for (DeliveryStatus status : STATUSES) {
            Assertions.assertEquals(expected.get(status), status.holdsRider(), status.name());
        }
    }


    @Test
    void holdsRider_shouldBeExactlyTheStatusesThatCanBeCancelled() {
        // The order.cancelled consumer cancels a delivery when it holds a rider; the table must allow exactly those.
        for (DeliveryStatus status : STATUSES) {
            Assertions.assertEquals(
                    status.holdsRider(),
                    status.canTransitionTo(DeliveryStatus.CANCELLED),
                    status.name()
            );
        }
    }


    @Test
    void everyEdge_shouldStartFromAStatusThatHoldsTheRider() {
        // So "the target releases the rider" (today's load rule) and "the delivery leaves a status that holds the
        // rider" (ADR 0002 section 4.2) release the rider on the same three edges.
        List<String> releasing = new ArrayList<>();
        for (DeliveryStatus from : STATUSES) {
            for (DeliveryStatus to : STATUSES) {
                if (!from.canTransitionTo(to)) {
                    continue;
                }
                Assertions.assertTrue(from.holdsRider(), from + " -> " + to);
                if (!to.holdsRider()) {
                    releasing.add(from + " -> " + to);
                }
            }
        }

        Assertions.assertEquals(
                List.of("ASSIGNED -> CANCELLED", "PICKED_UP -> DELIVERED", "PICKED_UP -> CANCELLED"),
                releasing
        );
    }


    @Test
    void eventType_shouldBeTheFulfilmentEventOfEachStatus() {
        Map<DeliveryStatus, String> expected = Map.of(
                DeliveryStatus.ASSIGNED, FulfilmentEvents.DELIVERY_ASSIGNED,
                DeliveryStatus.PICKED_UP, FulfilmentEvents.DELIVERY_PICKED_UP,
                DeliveryStatus.DELIVERED, FulfilmentEvents.DELIVERY_DELIVERED,
                DeliveryStatus.CANCELLED, FulfilmentEvents.DELIVERY_CANCELLED
        );

        for (DeliveryStatus status : STATUSES) {
            Assertions.assertEquals(expected.get(status), status.eventType(), status.name());
        }
        Assertions.assertEquals(
                List.of("delivery.assigned", "delivery.picked_up", "delivery.delivered", "delivery.cancelled"),
                STATUSES.stream().map(DeliveryStatus::eventType).toList()
        );
        // delivery.reassigned belongs to no status: a reassignment keeps the delivery's status.
        Assertions.assertTrue(STATUSES.stream().noneMatch(
                status -> FulfilmentEvents.DELIVERY_REASSIGNED.equals(status.eventType())));
    }
}
