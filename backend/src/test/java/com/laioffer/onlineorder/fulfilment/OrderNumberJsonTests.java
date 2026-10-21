package com.laioffer.onlineorder.fulfilment;


import com.laioffer.onlineorder.fulfilment.dispatch.DeliveryDto;
import com.laioffer.onlineorder.fulfilment.dispatch.DeliveryEntity;
import com.laioffer.onlineorder.fulfilment.dispatch.RiderDeliveryDto;
import com.laioffer.onlineorder.fulfilment.merchant.MerchantOrderDto;
import com.laioffer.onlineorder.messaging.contract.EventTestSupport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;


/**
 * The order's public number in the merchant's, the rider's and the admin's delivery bodies, as the application's JSON
 * mapper writes them: a string (it exceeds 2^53) when the row has one, and no order_no at all when it has none (rows
 * that only version 1 order events reached), also in the rider's view, which keeps every other null field.
 */
class OrderNumberJsonTests {

    private static final long ORDER_NO = 7_254_189_912_938_504_192L;
    private static final LocalDateTime ASSIGNED_AT = LocalDateTime.of(2026, 4, 25, 12, 30);


    @Test
    void merchantOrder_shouldWriteTheNumberAsAStringOrLeaveItOut() {
        JsonNode numbered = json(merchantOrder(ORDER_NO));
        JsonNode unnumbered = json(merchantOrder(null));

        Assertions.assertTrue(numbered.get("order_no").isString(), numbered.toString());
        Assertions.assertEquals("7254189912938504192", numbered.get("order_no").asString());
        Assertions.assertFalse(unnumbered.has("order_no"), unnumbered.toString());
    }


    @Test
    void riderDelivery_shouldWriteTheNumberAsAStringOrLeaveItOutWhileKeepingTheOtherNulls() {
        JsonNode numbered = json(new RiderDeliveryDto(delivery(ORDER_NO), "Burger King"));
        JsonNode unnumbered = json(new RiderDeliveryDto(delivery(null), "Burger King"));

        Assertions.assertEquals("7254189912938504192", numbered.get("order_no").asString());
        Assertions.assertFalse(unnumbered.has("order_no"), unnumbered.toString());
        Assertions.assertTrue(unnumbered.get("picked_up_at").isNull(), unnumbered.toString());
        Assertions.assertEquals(
                List.of("delivery_id", "order_id", "restaurant_id", "restaurant_name", "status", "assigned_at",
                        "picked_up_at", "delivered_at"),
                List.copyOf(unnumbered.propertyNames())
        );
    }


    @Test
    void adminDelivery_shouldWriteTheNumberAsAStringRightAfterTheOrderId() {
        JsonNode numbered = json(new DeliveryDto(delivery(ORDER_NO), "Alice"));

        Assertions.assertEquals("7254189912938504192", numbered.get("order_no").asString());
        Assertions.assertEquals(List.of("id", "order_id", "order_no", "customer_id", "restaurant_id", "rider_id",
                "rider_name", "status", "assigned_at"), List.copyOf(numbered.propertyNames()));
        Assertions.assertFalse(json(new DeliveryDto(delivery(null), "Alice")).has("order_no"));
    }


    private static MerchantOrderDto merchantOrder(Long orderNo) {
        return new MerchantOrderDto(10L, orderNo, 1L, 3L, "PAID", new BigDecimal("10.59"), "USD", List.of(), null,
                ASSIGNED_AT, ASSIGNED_AT);
    }


    private static DeliveryEntity delivery(Long orderNo) {
        return new DeliveryEntity(7L, 10L, orderNo, 3L, 1L, 4L, "ASSIGNED", ASSIGNED_AT, null, null, null, ASSIGNED_AT);
    }


    private static JsonNode json(Object body) {
        return EventTestSupport.mapper().readTree(EventTestSupport.mapper().writeValueAsString(body));
    }
}
