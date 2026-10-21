package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.repository.OrderRepository;
import org.springframework.stereotype.Component;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * Builds the customer's {@link AfterSalesCaseDto} of cases from their rows: lines, refund, order number and, for one
 * case, its history. The refund leaves out its last_error ({@link RefundDto#forCustomer}). Reads only, in the caller's
 * transaction (read-only for the queries, the command's own after a command).
 */
@Component
public class AfterSalesCaseViews {

    private final AfterSalesCaseLineRepository lineRepository;
    private final AfterSalesCaseHistoryRepository historyRepository;
    private final RefundRepository refundRepository;
    private final OrderRepository orderRepository;


    public AfterSalesCaseViews(
            AfterSalesCaseLineRepository lineRepository,
            AfterSalesCaseHistoryRepository historyRepository,
            RefundRepository refundRepository,
            OrderRepository orderRepository
    ) {
        this.lineRepository = lineRepository;
        this.historyRepository = historyRepository;
        this.refundRepository = refundRepository;
        this.orderRepository = orderRepository;
    }


    /**
     * One case with its history.
     */
    public AfterSalesCaseDto detail(AfterSalesCaseEntity current) {
        List<Long> caseIds = List.of(current.id());
        Long orderNo = orderRepository.findOrderNoById(current.orderId()).orElse(null);
        RefundDto refund = refundRepository.findAllByCaseIdIn(caseIds).stream()
                .findFirst()
                .map(row -> RefundDto.forCustomer(row, orderNo))
                .orElse(null);
        return new AfterSalesCaseDto(
                current,
                orderNo,
                lineRepository.findAllByCaseIdIn(caseIds).stream().map(AfterSalesLineDto::new).toList(),
                refund,
                historyRepository.findAllByCaseId(current.id()).stream().map(AfterSalesHistoryDto::new).toList()
        );
    }


    /**
     * Several cases, in their order, without their history: a few queries for the whole page.
     */
    public List<AfterSalesCaseDto> summaries(List<AfterSalesCaseEntity> cases) {
        if (cases.isEmpty()) {
            return List.of();
        }
        List<Long> caseIds = cases.stream().map(AfterSalesCaseEntity::id).toList();
        Map<Long, Long> orderNumbers = new HashMap<>();
        for (OrderEntity order : orderRepository.findAllById(
                cases.stream().map(AfterSalesCaseEntity::orderId).distinct().toList())) {
            orderNumbers.put(order.id(), order.orderNo());
        }
        Map<Long, List<AfterSalesLineDto>> linesByCase = new HashMap<>();
        for (AfterSalesCaseLineEntity line : lineRepository.findAllByCaseIdIn(caseIds)) {
            linesByCase.computeIfAbsent(line.caseId(), ignored -> new ArrayList<>()).add(new AfterSalesLineDto(line));
        }
        Map<Long, RefundEntity> refundByCase = new HashMap<>();
        for (RefundEntity refund : refundRepository.findAllByCaseIdIn(caseIds)) {
            refundByCase.put(refund.caseId(), refund);
        }
        List<AfterSalesCaseDto> results = new ArrayList<>();
        for (AfterSalesCaseEntity current : cases) {
            Long orderNo = orderNumbers.get(current.orderId());
            RefundEntity refund = refundByCase.get(current.id());
            results.add(new AfterSalesCaseDto(
                    current,
                    orderNo,
                    List.copyOf(linesByCase.getOrDefault(current.id(), List.of())),
                    refund == null ? null : RefundDto.forCustomer(refund, orderNo),
                    null
            ));
        }
        return results;
    }
}
