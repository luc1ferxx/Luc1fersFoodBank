package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.repository.IdempotencyRequestRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;


import java.time.LocalDateTime;


@Service
@Profile("!h2")
public class PostgresIdempotencyRequestInitializer implements IdempotencyRequestInitializer {

    private final IdempotencyRequestRepository idempotencyRequestRepository;


    public PostgresIdempotencyRequestInitializer(IdempotencyRequestRepository idempotencyRequestRepository) {
        this.idempotencyRequestRepository = idempotencyRequestRepository;
    }


    @Override
    public int insertIfAbsent(
            Long customerId,
            String scope,
            String idempotencyKey,
            String requestHash,
            String status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        return idempotencyRequestRepository.insertIfAbsent(
                customerId,
                scope,
                idempotencyKey,
                requestHash,
                status,
                createdAt,
                updatedAt
        );
    }
}
