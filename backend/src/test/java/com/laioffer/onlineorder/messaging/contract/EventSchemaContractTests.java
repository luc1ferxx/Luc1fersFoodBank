package com.laioffer.onlineorder.messaging.contract;


import com.laioffer.onlineorder.fulfilment.FulfilmentEvents;
import com.laioffer.onlineorder.model.OrderStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


/**
 * The CI check of the event contracts (README "Event contracts"): the published schemas are unchanged (SHA256SUMS), the
 * catalog matches the schema files, every version has sample events that it accepts, and every BACKWARD version accepts
 * the samples of the versions before it. The fixture sets under event-contract-fixtures show that each rule fails
 * when it is broken.
 */
class EventSchemaContractTests {

    private static final String SAMPLES = "event-samples/";
    private static final String FIXTURES = "event-contract-fixtures/";


    @Test
    void publishedSchemas_shouldMatchTheirChecksums() {
        assertNoProblems(published().manifestProblems());
    }


    @Test
    void catalog_shouldListExactlyThePublishedSchemaFilesWhichAreValidDraft202012Schemas() {
        assertNoProblems(published().catalogProblems(EventSchemaRegistry.published().catalog()));
    }


    @Test
    void everyPublishedVersion_shouldAcceptItsSamplesAndBackwardVersionsTheSamplesOfTheVersionsBefore() {
        assertNoProblems(published().sampleProblems(EventSchemaRegistry.published()));
    }


    @Test
    void everyEventTypeTheApplicationPublishes_shouldHaveAContractOnItsTopic() {
        List<String> orderEvents = Arrays.stream(OrderStatus.values()).map(OrderStatus::eventType).toList();
        List<String> deliveryEvents = List.of(
                FulfilmentEvents.DELIVERY_ASSIGNED,
                FulfilmentEvents.DELIVERY_PICKED_UP,
                FulfilmentEvents.DELIVERY_DELIVERED,
                FulfilmentEvents.DELIVERY_CANCELLED,
                FulfilmentEvents.DELIVERY_REASSIGNED
        );
        EventSchemaCatalog catalog = EventSchemaRegistry.published().catalog();

        List<String> missing = new ArrayList<>();
        orderEvents.forEach(type -> expectTopic(catalog, type, EventTopic.ORDER_EVENTS, missing));
        deliveryEvents.forEach(type -> expectTopic(catalog, type, EventTopic.DELIVERY_EVENTS, missing));
        expectTopic(catalog, FulfilmentEvents.MERCHANT_ORDER_RECEIVED, EventTopic.MERCHANT_EVENTS, missing);

        assertNoProblems(missing);
        Assertions.assertEquals(orderEvents.size() + deliveryEvents.size() + 1, catalog.eventTypes().size());
    }


    @Test
    void aBackwardVersionThatAddsAnOptionalProperty_shouldPass() {
        EventContractChecker checker = fixture("backward-compatible");

        assertNoProblems(checker.manifestProblems());
        assertNoProblems(checker.catalogProblems(fixtureRegistry("backward-compatible").catalog()));
        assertNoProblems(checker.sampleProblems(fixtureRegistry("backward-compatible")));
    }


    @Test
    void aBackwardVersionThatRequiresANewProperty_shouldBeReportedAsIncompatible() {
        List<String> problems = fixture("backward-incompatible").sampleProblems(fixtureRegistry("backward-incompatible"));

        Assertions.assertEquals(1, problems.size(), problems::toString);
        Assertions.assertTrue(problems.get(0).startsWith("order.paid v2 is marked BACKWARD but rejects"), problems::toString);
        Assertions.assertTrue(problems.get(0).contains("order.paid/v1/sample.json"), problems::toString);
        Assertions.assertTrue(problems.get(0).contains("tip"), problems::toString);
    }


    @Test
    void theSameChangeMarkedBreaking_shouldPassWhileThePreviousVersionIsKept() {
        EventSchemaRegistry registry = fixtureRegistry("breaking");

        assertNoProblems(fixture("breaking").catalogProblems(registry.catalog()));
        assertNoProblems(fixture("breaking").sampleProblems(registry));
    }


    @Test
    void aBreakingVersionWithoutThePreviousVersion_shouldBeReported() {
        EventSchemaRegistry registry = fixtureRegistry("breaking-without-previous");

        List<String> problems = fixture("breaking-without-previous").catalogProblems(registry.catalog());

        Assertions.assertEquals(1, problems.size(), problems::toString);
        Assertions.assertTrue(problems.get(0).startsWith("order.paid v2 is BREAKING but v1 is no longer listed"),
                problems::toString);
    }


    @Test
    void aChangedOrUnlistedSchemaFile_shouldBeReported() {
        List<String> problems = fixture("tampered").manifestProblems();

        Assertions.assertEquals(2, problems.size(), problems::toString);
        Assertions.assertTrue(problems.get(0).startsWith("order-events/order.paid/v1.json changed after it was published"),
                problems::toString);
        Assertions.assertTrue(problems.get(1).startsWith("order-events/order.paid/v2.json is not in SHA256SUMS. Add the line: "),
                problems::toString);
    }


    private static void expectTopic(EventSchemaCatalog catalog, String eventType, EventTopic topic, List<String> missing) {
        EventSchemaCatalog.EventTypeContract contract = catalog.eventTypes().get(eventType);
        if (contract == null) {
            missing.add(eventType + " has no contract in event-schemas/catalog.json");
        } else if (contract.topic() != topic) {
            missing.add(eventType + " is published on " + topic.contractName() + ", not " + contract.topic().contractName());
        }
    }


    private static EventContractChecker published() {
        return new EventContractChecker(EventSchemaRegistry.DEFAULT_LOCATION, SAMPLES);
    }


    private static EventContractChecker fixture(String name) {
        return new EventContractChecker(FIXTURES + name + "/schemas/", FIXTURES + name + "/samples/");
    }


    private static EventSchemaRegistry fixtureRegistry(String name) {
        return EventSchemaRegistry.load(FIXTURES + name + "/schemas/");
    }


    private static void assertNoProblems(List<String> problems) {
        Assertions.assertEquals(List.of(), problems, () -> String.join("\n", problems));
    }
}
