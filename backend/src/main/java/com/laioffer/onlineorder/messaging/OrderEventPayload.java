package com.laioffer.onlineorder.messaging;


import com.laioffer.onlineorder.model.OrderHistoryItemDto;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;


public record OrderEventPayload(
        Long customerId,
        String status,
        BigDecimal totalPrice,
        LocalDateTime createdAt,
        List<OrderHistoryItemDto> items
) {
}
