package com.laioffer.onlineorder.service;


import java.time.LocalDateTime;


public interface IdempotencyRequestInitializer {

    int insertIfAbsent(
            Long customerId,
            String scope,
            String idempotencyKey,
            String requestHash,
            String status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    );
}
