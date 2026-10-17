package com.laioffer.onlineorder.messaging.contract;


import java.util.Arrays;
import java.util.Optional;


/**
 * The topics whose events have published contracts, by their contract name: the directory of their schemas under
 * {@code event-schemas/}. The Kafka topic names themselves are configurable (app.kafka.order-topic,
 * app.fulfilment.delivery-topic, app.fulfilment.merchant-topic) and default to the contract names.
 */
public enum EventTopic {

    ORDER_EVENTS("order-events"),
    DELIVERY_EVENTS("delivery-events"),
    MERCHANT_EVENTS("merchant-events");

    private final String contractName;


    EventTopic(String contractName) {
        this.contractName = contractName;
    }


    public String contractName() {
        return contractName;
    }


    public static Optional<EventTopic> ofContractName(String contractName) {
        return Arrays.stream(values()).filter(topic -> topic.contractName.equals(contractName)).findFirst();
    }
}
