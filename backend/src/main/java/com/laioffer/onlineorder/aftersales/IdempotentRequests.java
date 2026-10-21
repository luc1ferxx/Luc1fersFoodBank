package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.entity.IdempotencyRequestEntity;
import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.repository.IdempotencyRequestRepository;
import com.laioffer.onlineorder.service.IdempotencyRequestInitializer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Supplier;


/**
 * The Idempotency-Key handling of the after-sales customer and admin commands (ADR 0002 D10 and section 20), on the
 * idempotency_requests table the order commands use. A request runs once per customer, scope and key, in the
 * caller's transaction: its row is inserted first and marked SUCCEEDED with the order and the after-sales case the
 * request opened or changed, so it commits or rolls back with the request. A replay answers with that case, never
 * with whatever case the order has now: an order can have two customer requests, and a new key for the second one
 * must not find the first. The order commands keep their own copy of this logic ({@code OrderService}).
 *
 * <p>Lock order: the idempotency row, then whatever the request locks.
 */
@Component
public class IdempotentRequests {

    static final int KEY_MAX_LENGTH = 255;

    private static final String PROCESSING = "PROCESSING";
    private static final String SUCCEEDED = "SUCCEEDED";

    private final IdempotencyRequestRepository idempotencyRequestRepository;
    private final IdempotencyRequestInitializer idempotencyRequestInitializer;


    public IdempotentRequests(
            IdempotencyRequestRepository idempotencyRequestRepository,
            IdempotencyRequestInitializer idempotencyRequestInitializer
    ) {
        this.idempotencyRequestRepository = idempotencyRequestRepository;
        this.idempotencyRequestInitializer = idempotencyRequestInitializer;
    }


    /**
     * The trimmed key.
     *
     * @throws BadRequestException when it is missing, blank or longer than 255 characters
     */
    public static String normalizeKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key header is required");
        }
        String normalizedKey = idempotencyKey.trim();
        if (normalizedKey.length() > KEY_MAX_LENGTH) {
            throw new BadRequestException("Idempotency-Key header must be 255 characters or fewer");
        }
        return normalizedKey;
    }


    /**
     * The SHA-256 of {@code text} in lower-case hex: the request hash of a canonical request, or the digest of a free
     * text inside one.
     */
    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }


    /**
     * Runs {@code request} once for this customer, scope and key.
     *
     * @param idempotencyKey a key from {@link #normalizeKey}
     * @param requestHash    the hash of the canonical request; the same key with another request is refused
     * @param request        the request; returns the order and the case it opened or changed
     * @return the case the request is bound to: the one {@code request} returned, or for a replay the one the first
     *         run stored
     * @throws ConflictException 409 CONFLICT when the key was used with another request, or when its first run is
     *                           still processing
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long run(
            Long customerId,
            String scope,
            String idempotencyKey,
            String requestHash,
            Supplier<Binding> request
    ) {
        LocalDateTime now = LocalDateTime.now();
        int inserted = idempotencyRequestInitializer.insertIfAbsent(
                customerId,
                scope,
                idempotencyKey,
                requestHash,
                PROCESSING,
                now,
                now
        );
        IdempotencyRequestEntity stored = idempotencyRequestRepository.lockByCustomerIdAndScopeAndIdempotencyKey(
                customerId,
                scope,
                idempotencyKey
        );
        if (stored == null) {
            throw new ConflictException("Unable to initialize idempotent after-sales request");
        }
        if (!stored.requestHash().equals(requestHash)) {
            throw new ConflictException("Idempotency key was already used with a different request");
        }
        if (SUCCEEDED.equals(stored.status())) {
            if (stored.caseId() == null) {
                throw new ConflictException("Idempotent after-sales request completed without a stored case");
            }
            return stored.caseId();
        }
        if (!PROCESSING.equals(stored.status()) || inserted == 0) {
            throw new ConflictException("Idempotent after-sales request is still processing");
        }

        Binding binding = Objects.requireNonNull(request.get(), "binding");
        idempotencyRequestRepository.markSucceededWithCase(
                stored.id(),
                SUCCEEDED,
                binding.orderId(),
                binding.caseId(),
                LocalDateTime.now()
        );
        return binding.caseId();
    }


    /**
     * What a request opened or changed.
     */
    public record Binding(Long orderId, Long caseId) {

        public Binding {
            Objects.requireNonNull(orderId, "orderId");
            Objects.requireNonNull(caseId, "caseId");
        }
    }
}
