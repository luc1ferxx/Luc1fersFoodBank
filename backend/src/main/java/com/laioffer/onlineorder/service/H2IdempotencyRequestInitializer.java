package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.entity.IdempotencyRequestEntity;
import com.laioffer.onlineorder.repository.IdempotencyRequestRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;


import java.time.LocalDateTime;


@Service
@Profile("h2")
public class H2IdempotencyRequestInitializer implements IdempotencyRequestInitializer {

    private final IdempotencyRequestRepository idempotencyRequestRepository;


    public H2IdempotencyRequestInitializer(IdempotencyRequestRepository idempotencyRequestRepository) {
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
        IdempotencyRequestEntity existingRequest = idempotencyRequestRepository.lockByCustomerIdAndScopeAndIdempotencyKey(
                customerId,
                scope,
                idempotencyKey
        );

        if (existingRequest != null) {
            return 0;
        }

        idempotencyRequestRepository.save(new IdempotencyRequestEntity(
                null,
                customerId,
                scope,
                idempotencyKey,
                requestHash,
                status,
                null,
                createdAt,
                updatedAt
        ));
        return 1;
    }
}
