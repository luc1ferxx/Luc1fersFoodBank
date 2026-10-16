package com.laioffer.onlineorder.messaging;


import java.time.LocalDateTime;


/**
 * @param paidAt          when the order was paid (the order.paid event time). The accept timeout runs from the
 *                        envelope's occurredAt, when the inbox received the order (never before this)
 * @param activeMerchants active merchant accounts of the restaurant when the inbox received the order (at least 1)
 */
public record MerchantEventPayload(
        Long orderId,
        Long restaurantId,
        LocalDateTime paidAt,
        Integer activeMerchants
) {
}
