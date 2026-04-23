package com.laioffer.onlineorder.messaging;


import com.laioffer.onlineorder.model.OrderHistoryItemDto;


import java.time.LocalDateTime;
import java.util.List;


public record OrderEventPayload(
        Long customerId,
        String status,
        Double totalPrice,
        LocalDateTime createdAt,
        List<OrderHistoryItemDto> items
) {
}
