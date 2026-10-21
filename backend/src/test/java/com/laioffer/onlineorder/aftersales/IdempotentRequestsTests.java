package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.entity.IdempotencyRequestEntity;
import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.repository.IdempotencyRequestRepository;
import com.laioffer.onlineorder.service.IdempotencyRequestInitializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;


import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;


/**
 * The after-sales Idempotency-Key handling (ADR 0002 D10 and section 20): a first run inserts its row, runs the
 * request and binds the row to the request's case; a replay answers with that case without running anything; a key
 * reused for another request, or one whose first run never finished, is refused.
 */
@ExtendWith(MockitoExtension.class)
class IdempotentRequestsTests {

    private static final Long CUSTOMER_ID = 3L;

    private static final String SCOPE = "after-sales-open:v1:1001";

    private static final String KEY = "open-1";

    private static final String HASH = "hash-1";

    @Mock
    private IdempotencyRequestRepository repository;

    @Mock
    private IdempotencyRequestInitializer initializer;

    private IdempotentRequests requests;

    private final AtomicInteger runs = new AtomicInteger();


    @BeforeEach
    void setup() {
        requests = new IdempotentRequests(repository, initializer);
    }


    @Test
    void run_forANewKey_shouldRunTheRequestAndBindTheKeyToItsCase() {
        stubStored(1, row("PROCESSING", HASH, null));

        Long caseId = requests.run(CUSTOMER_ID, SCOPE, KEY, HASH, request(501L));

        Assertions.assertEquals(501L, caseId);
        Assertions.assertEquals(1, runs.get());
        InOrder order = Mockito.inOrder(initializer, repository);
        order.verify(initializer).insertIfAbsent(Mockito.eq(CUSTOMER_ID), Mockito.eq(SCOPE), Mockito.eq(KEY),
                Mockito.eq(HASH), Mockito.eq("PROCESSING"), Mockito.any(), Mockito.any());
        order.verify(repository).lockByCustomerIdAndScopeAndIdempotencyKey(CUSTOMER_ID, SCOPE, KEY);
        order.verify(repository).markSucceededWithCase(Mockito.eq(9L), Mockito.eq("SUCCEEDED"), Mockito.eq(1001L),
                Mockito.eq(501L), Mockito.any());
    }


    @Test
    void run_forAReplay_shouldAnswerWithTheBoundCaseWithoutRunning() {
        stubStored(0, row("SUCCEEDED", HASH, 501L));

        Assertions.assertEquals(501L, requests.run(CUSTOMER_ID, SCOPE, KEY, HASH, request(777L)));

        Assertions.assertEquals(0, runs.get());
        Mockito.verify(repository, Mockito.never()).markSucceededWithCase(Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any());
    }


    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "null", value = {
            "1|PROCESSING|hash-2|null|Idempotency key was already used with a different request",
            "0|SUCCEEDED|hash-1|null|Idempotent after-sales request completed without a stored case",
            "0|PROCESSING|hash-1|null|Idempotent after-sales request is still processing",
            "1|FAILED|hash-1|null|Idempotent after-sales request is still processing"
    })
    void run_whenTheKeysRowDoesNotAllowARun_shouldBeAConflict(
            int inserted,
            String status,
            String storedHash,
            Long caseId,
            String message
    ) {
        stubStored(inserted, row(status, storedHash, caseId));

        ConflictException refused = Assertions.assertThrows(ConflictException.class,
                () -> requests.run(CUSTOMER_ID, SCOPE, KEY, HASH, request(501L)));

        Assertions.assertEquals("CONFLICT", refused.getCode());
        Assertions.assertEquals(message, refused.getMessage());
        Assertions.assertEquals(0, runs.get());
    }


    @Test
    void run_whenTheRowCannotBeRead_shouldBeAConflict() {
        stubStored(0, null);

        Assertions.assertEquals("Unable to initialize idempotent after-sales request", Assertions.assertThrows(
                ConflictException.class, () -> requests.run(CUSTOMER_ID, SCOPE, KEY, HASH, request(501L)))
                .getMessage());
    }


    @Test
    void normalizeKey_shouldTrimAndRefuseAMissingOrLongKey() {
        Assertions.assertEquals("open-1", IdempotentRequests.normalizeKey("  open-1 "));
        Assertions.assertEquals("k".repeat(255), IdempotentRequests.normalizeKey("k".repeat(255)));
        for (String key : new String[]{null, "", "   "}) {
            Assertions.assertEquals("Idempotency-Key header is required",
                    Assertions.assertThrows(BadRequestException.class, () -> IdempotentRequests.normalizeKey(key))
                            .getMessage());
        }
        Assertions.assertEquals("Idempotency-Key header must be 255 characters or fewer",
                Assertions.assertThrows(BadRequestException.class,
                        () -> IdempotentRequests.normalizeKey("k".repeat(256))).getMessage());
    }


    @Test
    void sha256_shouldBeTheLowerCaseHexDigest() {
        Assertions.assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                IdempotentRequests.sha256("hello"));
    }


    @Test
    void binding_shouldNameAnOrderAndACase() {
        Assertions.assertThrows(NullPointerException.class, () -> new IdempotentRequests.Binding(null, 1L));
        Assertions.assertThrows(NullPointerException.class, () -> new IdempotentRequests.Binding(1L, null));
    }


    private void stubStored(int inserted, IdempotencyRequestEntity stored) {
        Mockito.when(initializer.insertIfAbsent(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(inserted);
        Mockito.when(repository.lockByCustomerIdAndScopeAndIdempotencyKey(CUSTOMER_ID, SCOPE, KEY)).thenReturn(stored);
    }


    private Supplier<IdempotentRequests.Binding> request(Long caseId) {
        return () -> {
            runs.incrementAndGet();
            return new IdempotentRequests.Binding(1001L, caseId);
        };
    }


    private static IdempotencyRequestEntity row(String status, String hash, Long caseId) {
        LocalDateTime createdAt = LocalDateTime.of(2026, 10, 21, 12, 0);
        return new IdempotencyRequestEntity(9L, CUSTOMER_ID, SCOPE, KEY, hash, status, caseId == null ? null : 1001L,
                caseId, createdAt, createdAt);
    }
}
