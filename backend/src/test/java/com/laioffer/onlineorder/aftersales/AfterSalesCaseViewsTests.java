package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.repository.OrderRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;


/**
 * The answers about cases: one case with its lines, refund, order number and history; a page of cases without their
 * history, with a few queries for the whole page.
 */
@ExtendWith(MockitoExtension.class)
class AfterSalesCaseViewsTests {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 12, 0, 30);

    @Mock
    private AfterSalesCaseLineRepository lineRepository;

    @Mock
    private AfterSalesCaseHistoryRepository historyRepository;

    @Mock
    private RefundRepository refundRepository;

    @Mock
    private OrderRepository orderRepository;

    private AfterSalesCaseViews views;


    @BeforeEach
    void setup() {
        views = new AfterSalesCaseViews(lineRepository, historyRepository, refundRepository, orderRepository);
    }


    @Test
    void detail_shouldCarryTheLinesRefundAndHistory() {
        AfterSalesCaseEntity refunding = caseRow(501L, 1001L, "REFUNDING");
        Mockito.when(orderRepository.findOrderNoById(1001L)).thenReturn(Optional.of(9001L));
        Mockito.when(lineRepository.findAllByCaseIdIn(List.of(501L))).thenReturn(List.of(line(501L, 41L)));
        Mockito.when(refundRepository.findAllByCaseIdIn(List.of(501L))).thenReturn(List.of(refund(501L, 1001L)));
        AfterSalesCaseHistoryEntity opened = new AfterSalesCaseHistoryEntity(1L, 501L, null, "PENDING_MERCHANT",
                "OPEN", "CUSTOMER", 3L, new BigDecimal("9.00"), "USD", null, "trace", NOW);
        Mockito.when(historyRepository.findAllByCaseId(501L)).thenReturn(List.of(opened));

        AfterSalesCaseDto dto = views.detail(refunding);

        Assertions.assertEquals(501L, dto.id());
        Assertions.assertEquals(9001L, dto.orderNo());
        Assertions.assertEquals(List.of(new AfterSalesLineDto(41L, "Fries", 2, new BigDecimal("4.50"),
                new BigDecimal("9.00"), "USD")), dto.lines());
        Assertions.assertEquals(new RefundDto(70L, 501L, 1001L, 9001L, new BigDecimal("9.00"), "USD", "PENDING", 0,
                null, null, NOW, NOW, null), dto.refund());
        Assertions.assertEquals(List.of(new AfterSalesHistoryDto("OPEN", null, "PENDING_MERCHANT", "CUSTOMER",
                new BigDecimal("9.00"), "USD", null, NOW)), dto.history());
    }


    /**
     * The gateway's error of a failed attempt is for the platform's staff: neither the detail nor a page shows it to the
     * customer, while every other refund field stays.
     */
    @Test
    void refundOfACustomersCase_shouldLeaveOutItsLastError() {
        AfterSalesCaseEntity refunding = caseRow(501L, 1001L, "REFUNDING");
        RefundEntity failing = new RefundEntity(70L, 501L, 1001L, 3L, "after-sales-case:501", "SIMPAY-1",
                new BigDecimal("9.00"), "USD", "PENDING", 2, NOW.plusSeconds(20), null, null,
                "java.net.SocketTimeoutException: Read timed out", 4L, NOW, NOW, null);
        RefundDto expected = new RefundDto(70L, 501L, 1001L, 9001L, new BigDecimal("9.00"), "USD", "PENDING", 2, null,
                null, NOW.plusSeconds(20), NOW, null);
        Mockito.when(orderRepository.findOrderNoById(1001L)).thenReturn(Optional.of(9001L));
        Mockito.when(orderRepository.findAllById(List.of(1001L))).thenReturn(List.of(order(1001L, 9001L)));
        Mockito.when(refundRepository.findAllByCaseIdIn(List.of(501L))).thenReturn(List.of(failing));

        Assertions.assertEquals(expected, views.detail(refunding).refund());
        Assertions.assertEquals(expected, views.summaries(List.of(refunding)).get(0).refund());
        // The full view, for the staff, keeps it.
        Assertions.assertEquals("java.net.SocketTimeoutException: Read timed out",
                new RefundDto(failing, 9001L).lastError());
    }


    @Test
    void detail_ofACaseWithoutARefundOrAnOrderNumber_shouldLeaveThemOut() {
        Mockito.when(orderRepository.findOrderNoById(1001L)).thenReturn(Optional.empty());

        AfterSalesCaseDto dto = views.detail(caseRow(501L, 1001L, "PENDING_MERCHANT"));

        Assertions.assertNull(dto.orderNo());
        Assertions.assertNull(dto.refund());
        Assertions.assertEquals(List.of(), dto.lines());
        Assertions.assertEquals(List.of(), dto.history());
    }


    @Test
    void summaries_shouldKeepThePagesOrderWithoutHistory() {
        Assertions.assertEquals(List.of(), views.summaries(List.of()));
        Mockito.verifyNoInteractions(lineRepository, refundRepository, orderRepository, historyRepository);

        AfterSalesCaseEntity newer = caseRow(502L, 1002L, "PENDING_MERCHANT");
        AfterSalesCaseEntity older = caseRow(501L, 1001L, "REFUNDING");
        AfterSalesCaseEntity sameOrder = caseRow(500L, 1001L, "CLOSED");
        Mockito.when(orderRepository.findAllById(List.of(1002L, 1001L))).thenReturn(List.of(
                order(1001L, 9001L), order(1002L, 9002L)));
        Mockito.when(lineRepository.findAllByCaseIdIn(List.of(502L, 501L, 500L)))
                .thenReturn(List.of(line(500L, 40L), line(501L, 41L), line(501L, 42L), line(502L, 43L)));
        Mockito.when(refundRepository.findAllByCaseIdIn(List.of(502L, 501L, 500L)))
                .thenReturn(List.of(refund(501L, 1001L)));

        List<AfterSalesCaseDto> page = views.summaries(List.of(newer, older, sameOrder));

        Assertions.assertEquals(List.of(502L, 501L, 500L), page.stream().map(AfterSalesCaseDto::id).toList());
        Assertions.assertEquals(List.of(9002L, 9001L, 9001L), page.stream().map(AfterSalesCaseDto::orderNo).toList());
        Assertions.assertEquals(List.of(1, 2, 1), page.stream().map(dto -> dto.lines().size()).toList());
        Assertions.assertNull(page.get(0).refund());
        Assertions.assertEquals(9001L, page.get(1).refund().orderNo());
        Assertions.assertTrue(page.stream().allMatch(dto -> dto.history() == null), "lists carry no history");
        Mockito.verifyNoInteractions(historyRepository);
    }


    private static AfterSalesCaseEntity caseRow(Long id, Long orderId, String status) {
        boolean refunding = "REFUNDING".equals(status);
        return new AfterSalesCaseEntity(id, orderId, 3L, 7L, "CUSTOMER_REQUEST", 1, status, "OTHER", "x",
                new BigDecimal("9.00"), new BigDecimal("9.00"), null, refunding ? new BigDecimal("9.00") : null, null,
                "USD", null, null, null, "CLOSED".equals(status) ? "WITHDRAWN" : null, null,
                "PENDING_MERCHANT".equals(status) ? NOW.plusHours(1) : null, null, 0, 1L, NOW, NOW);
    }


    private static AfterSalesCaseLineEntity line(Long caseId, Long orderItemId) {
        return new AfterSalesCaseLineEntity(orderItemId + 100, caseId, orderItemId, "Fries", new BigDecimal("4.50"), 2,
                new BigDecimal("9.00"), "USD");
    }


    private static RefundEntity refund(Long caseId, Long orderId) {
        return new RefundEntity(70L, caseId, orderId, 3L, "after-sales-case:" + caseId, "SIMPAY-1",
                new BigDecimal("9.00"), "USD", "PENDING", 0, NOW, null, null, null, 0L, NOW, NOW, null);
    }


    private static OrderEntity order(Long id, Long orderNo) {
        return new OrderEntity(id, orderNo, 3L, new BigDecimal("9.00"), "COMPLETED", NOW, 7L, null, NOW, "USD");
    }
}
