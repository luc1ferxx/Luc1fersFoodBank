package com.laioffer.onlineorder.fulfilment.merchant;


import com.laioffer.onlineorder.datasource.ReadOnlyTransactions;
import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.fulfilment.order.OrderActor;
import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands;
import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands.AfterSalesDecision;
import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands.AfterSalesResponse;
import com.laioffer.onlineorder.messaging.AfterSalesCaseLinePayload;
import com.laioffer.onlineorder.messaging.AfterSalesCasePayload;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;


/**
 * The merchant's after-sales commands and inbox (ADR 0002 section 11.3): the account check comes first and refuses a
 * deactivated or unbound merchant before anything is locked; the inbox row is locked by case and restaurant before the
 * order module locks the case; the row is then brought to the case the command returns, by version; a refused answer
 * leaves the row alone, and only an answer that changed the case is counted.
 */
@ExtendWith(MockitoExtension.class)
class MerchantAfterSalesServiceTests {

    private static final long MERCHANT_CUSTOMER_ID = 40L;
    private static final long RESTAURANT_ID = 2L;
    private static final long CASE_ID = 300L;
    private static final long ROW_ID = 1L;
    private static final OrderActor MERCHANT = OrderActor.merchant(MERCHANT_CUSTOMER_ID);
    private static final LocalDateTime OPENED_AT = LocalDateTime.of(2026, 10, 20, 9, 0);
    private static final LocalDateTime DEADLINE = OPENED_AT.plusHours(24);

    @Mock
    private MerchantAccountRepository merchantAccountRepository;

    @Mock
    private MerchantAfterSalesRepository merchantAfterSalesRepository;

    @Mock
    private OrderLifecycleCommands orderLifecycleCommands;

    @Mock
    private ApplicationMetricsService metricsService;

    private MerchantAfterSalesService service;


    @BeforeEach
    void setup() {
        service = new MerchantAfterSalesService(
                merchantAccountRepository,
                merchantAfterSalesRepository,
                new MerchantAfterSalesProjection(merchantAfterSalesRepository, JsonMapper.builder()
                        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                        .build()),
                orderLifecycleCommands,
                metricsService,
                new ReadOnlyTransactions(Mockito.mock(PlatformTransactionManager.class))
        );
    }


    @Test
    void approve_shouldCheckTheAccountLockTheInboxRowAnswerAndThenBringTheRowToTheReturnedCase() {
        bindMerchant(true);
        Mockito.when(merchantAfterSalesRepository.lockByCaseIdAndRestaurantId(CASE_ID, RESTAURANT_ID))
                .thenReturn(row("PENDING_MERCHANT", 0L));
        Mockito.when(orderLifecycleCommands.decideAfterSalesCase(
                CASE_ID, RESTAURANT_ID, AfterSalesResponse.approve(), MERCHANT))
                .thenReturn(new AfterSalesDecision(snapshot("REFUNDING", 1L, "APPROVED", null), true));
        Mockito.when(merchantAfterSalesRepository.updateIfNewer(
                Mockito.eq(CASE_ID), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(1);
        Mockito.when(merchantAfterSalesRepository.findById(ROW_ID)).thenReturn(Optional.of(row("REFUNDING", 1L)));

        MerchantAfterSalesCaseDto approved = service.approve(MERCHANT_CUSTOMER_ID, CASE_ID);

        Assertions.assertEquals("REFUNDING", approved.status());
        Assertions.assertEquals(CASE_ID, approved.caseId());
        Assertions.assertEquals(9030L, approved.orderNo());
        Assertions.assertEquals(List.of(new MerchantAfterSalesCaseDto.Line(
                77L, "Whopper Meal", 2, new BigDecimal("8.00"), new BigDecimal("16.00"), "USD")), approved.lines());
        InOrder steps = Mockito.inOrder(merchantAccountRepository, merchantAfterSalesRepository, orderLifecycleCommands);
        steps.verify(merchantAccountRepository).findByCustomerId(MERCHANT_CUSTOMER_ID);
        steps.verify(merchantAfterSalesRepository).lockByCaseIdAndRestaurantId(CASE_ID, RESTAURANT_ID);
        steps.verify(orderLifecycleCommands).decideAfterSalesCase(
                CASE_ID, RESTAURANT_ID, AfterSalesResponse.approve(), MERCHANT);
        // The row exists (it is locked): the upsert's insert does nothing and the update brings it to version 1.
        steps.verify(merchantAfterSalesRepository).insertIfAbsent(
                Mockito.eq(CASE_ID), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.eq(1L), Mockito.any(),
                Mockito.any());
        steps.verify(merchantAfterSalesRepository).updateIfNewer(
                Mockito.eq(CASE_ID),
                Mockito.eq("REFUNDING"),
                Mockito.isNull(),
                Mockito.eq(new BigDecimal("16.00")),
                Mockito.eq("APPROVED"),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.eq(1L),
                Mockito.any(LocalDateTime.class)
        );
        steps.verify(merchantAfterSalesRepository).findById(ROW_ID);
        Mockito.verifyNoMoreInteractions(merchantAccountRepository, merchantAfterSalesRepository, orderLifecycleCommands);
        Mockito.verify(metricsService).recordAfterSalesMerchantDecision("approved");
    }


    @Test
    void rejectAndOffer_shouldPassTheMerchantsAnswerThrough() {
        bindMerchant(true);
        Mockito.when(merchantAfterSalesRepository.lockByCaseIdAndRestaurantId(CASE_ID, RESTAURANT_ID))
                .thenReturn(row("PENDING_MERCHANT", 0L));
        Mockito.when(orderLifecycleCommands.decideAfterSalesCase(
                Mockito.eq(CASE_ID), Mockito.eq(RESTAURANT_ID), Mockito.any(), Mockito.eq(MERCHANT)))
                .thenReturn(new AfterSalesDecision(snapshot("REJECTED", 1L, "REJECTED", "Nothing was missing."), true));
        Mockito.when(merchantAfterSalesRepository.findById(ROW_ID)).thenReturn(Optional.of(row("REJECTED", 1L)));

        service.reject(MERCHANT_CUSTOMER_ID, CASE_ID, "Nothing was missing.");
        service.offer(MERCHANT_CUSTOMER_ID, CASE_ID, new BigDecimal("4.00"), "Half the fries");

        Mockito.verify(orderLifecycleCommands).decideAfterSalesCase(
                CASE_ID, RESTAURANT_ID, AfterSalesResponse.reject("Nothing was missing."), MERCHANT);
        Mockito.verify(orderLifecycleCommands).decideAfterSalesCase(
                CASE_ID, RESTAURANT_ID, AfterSalesResponse.offer(new BigDecimal("4.00"), "Half the fries"), MERCHANT);
        Mockito.verify(metricsService).recordAfterSalesMerchantDecision("rejected");
        Mockito.verify(metricsService).recordAfterSalesMerchantDecision("offered");
    }


    @Test
    void aRepeatedAnswer_shouldNotBeCountedAgain() {
        bindMerchant(true);
        Mockito.when(merchantAfterSalesRepository.lockByCaseIdAndRestaurantId(CASE_ID, RESTAURANT_ID))
                .thenReturn(row("REFUNDING", 1L));
        Mockito.when(orderLifecycleCommands.decideAfterSalesCase(
                CASE_ID, RESTAURANT_ID, AfterSalesResponse.approve(), MERCHANT))
                .thenReturn(new AfterSalesDecision(snapshot("REFUNDING", 1L, "APPROVED", null), false));
        Mockito.when(merchantAfterSalesRepository.findById(ROW_ID)).thenReturn(Optional.of(row("REFUNDING", 1L)));

        Assertions.assertEquals("REFUNDING", service.approve(MERCHANT_CUSTOMER_ID, CASE_ID).status());

        Mockito.verifyNoInteractions(metricsService);
    }


    @Test
    void aCaseThatIsNotInThisRestaurantsInbox_shouldBeNotFoundWithoutAnsweringIt() {
        bindMerchant(true);
        Mockito.when(merchantAfterSalesRepository.lockByCaseIdAndRestaurantId(CASE_ID, RESTAURANT_ID)).thenReturn(null);

        ResourceNotFoundException missing = Assertions.assertThrows(
                ResourceNotFoundException.class, () -> service.approve(MERCHANT_CUSTOMER_ID, CASE_ID));

        Assertions.assertEquals("After-sales case not found", missing.getMessage());
        Mockito.verifyNoInteractions(orderLifecycleCommands, metricsService);
    }


    @Test
    void aRefusedAnswer_shouldPropagateTheConflictWithoutTouchingTheRow() {
        bindMerchant(true);
        Mockito.when(merchantAfterSalesRepository.lockByCaseIdAndRestaurantId(CASE_ID, RESTAURANT_ID))
                .thenReturn(row("PENDING_MERCHANT", 0L));
        Mockito.when(orderLifecycleCommands.decideAfterSalesCase(
                CASE_ID, RESTAURANT_ID, AfterSalesResponse.approve(), MERCHANT))
                .thenThrow(new ConflictException(OrderLifecycleCommands.AFTER_SALES_DEADLINE_PASSED, "Too late"));

        Assertions.assertThrows(ConflictException.class, () -> service.approve(MERCHANT_CUSTOMER_ID, CASE_ID));

        Mockito.verify(merchantAfterSalesRepository).lockByCaseIdAndRestaurantId(CASE_ID, RESTAURANT_ID);
        Mockito.verifyNoMoreInteractions(merchantAfterSalesRepository);
        Mockito.verifyNoInteractions(metricsService);
    }


    @Test
    void everyRequest_ofADeactivatedOrUnboundMerchant_shouldBeForbiddenBeforeAnythingIsLocked() {
        bindMerchant(false);
        Assertions.assertThrows(AccessDeniedException.class, () -> service.approve(MERCHANT_CUSTOMER_ID, CASE_ID));
        Assertions.assertThrows(AccessDeniedException.class,
                () -> service.reject(MERCHANT_CUSTOMER_ID, CASE_ID, "No"));
        Assertions.assertThrows(AccessDeniedException.class,
                () -> service.offer(MERCHANT_CUSTOMER_ID, CASE_ID, BigDecimal.ONE, null));
        Assertions.assertThrows(AccessDeniedException.class,
                () -> service.getCases(MERCHANT_CUSTOMER_ID, null, 50));

        Mockito.when(merchantAccountRepository.findByCustomerId(MERCHANT_CUSTOMER_ID)).thenReturn(null);
        Assertions.assertThrows(AccessDeniedException.class, () -> service.approve(MERCHANT_CUSTOMER_ID, CASE_ID));
        Assertions.assertThrows(AccessDeniedException.class, () -> service.approve(null, CASE_ID));

        Mockito.verifyNoInteractions(merchantAfterSalesRepository, orderLifecycleCommands, metricsService);
    }


    @Test
    void getCases_shouldReadTheRestaurantsPageOfAStatus() {
        bindMerchant(true);
        Mockito.when(merchantAfterSalesRepository.findPageByRestaurantIdAndStatus(RESTAURANT_ID, "PENDING_MERCHANT", 100))
                .thenReturn(List.of(row("PENDING_MERCHANT", 0L)));
        Mockito.when(merchantAfterSalesRepository.findPageByRestaurantId(RESTAURANT_ID, 1)).thenReturn(List.of());

        List<MerchantAfterSalesCaseDto> pending = service.getCases(MERCHANT_CUSTOMER_ID, " pending_merchant ", 500);
        Assertions.assertEquals(1, pending.size());
        Assertions.assertEquals(DEADLINE, pending.get(0).deadlineAt());
        Assertions.assertEquals("USD", pending.get(0).currency());
        Assertions.assertEquals(List.of(), service.getCases(MERCHANT_CUSTOMER_ID, "", 0));

        BadRequestException unknown = Assertions.assertThrows(
                BadRequestException.class, () -> service.getCases(MERCHANT_CUSTOMER_ID, "OPEN", 50));
        Assertions.assertEquals("Unsupported after-sales status: OPEN", unknown.getMessage());
    }


    private void bindMerchant(boolean active) {
        Mockito.when(merchantAccountRepository.findByCustomerId(MERCHANT_CUSTOMER_ID)).thenReturn(new MerchantAccountEntity(
                1L, MERCHANT_CUSTOMER_ID, RESTAURANT_ID, active, LocalDateTime.now(), LocalDateTime.now()));
    }


    private static MerchantAfterSalesEntity row(String status, long version) {
        return new MerchantAfterSalesEntity(
                ROW_ID,
                CASE_ID,
                30L,
                9030L,
                RESTAURANT_ID,
                3L,
                status,
                "MISSING_ITEM",
                "The fries were missing.",
                """
                        [{"order_item_id": 77, "menu_item_name": "Whopper Meal", "quantity": 2, "unit_price": 8.00,
                          "amount": 16.00, "currency": "USD"}]
                        """,
                new BigDecimal("16.00"),
                null,
                "REFUNDING".equals(status) ? new BigDecimal("16.00") : null,
                "USD",
                null,
                null,
                "PENDING_MERCHANT".equals(status) ? DEADLINE : null,
                version,
                OPENED_AT,
                OPENED_AT
        );
    }


    private static AfterSalesCasePayload snapshot(String status, long version, String decision, String note) {
        return new AfterSalesCasePayload(
                CASE_ID,
                30L,
                9030L,
                3L,
                RESTAURANT_ID,
                "CUSTOMER_REQUEST",
                1,
                status,
                "MERCHANT_APPROVE",
                "MERCHANT",
                MERCHANT_CUSTOMER_ID,
                version,
                "MISSING_ITEM",
                "The fries were missing.",
                new BigDecimal("16.00"),
                null,
                "REFUNDING".equals(status) ? new BigDecimal("16.00") : null,
                null,
                "USD",
                decision,
                note,
                "REFUNDING".equals(status) ? "MERCHANT_APPROVED" : null,
                null,
                null,
                List.of(new AfterSalesCaseLinePayload(
                        77L, "Whopper Meal", 2, new BigDecimal("8.00"), new BigDecimal("16.00"), "USD")),
                OPENED_AT
        );
    }
}
