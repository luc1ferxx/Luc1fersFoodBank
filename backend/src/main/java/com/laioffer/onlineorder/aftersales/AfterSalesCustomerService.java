package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.entity.OrderHistoryItemEntity;
import com.laioffer.onlineorder.entity.OrderPaymentEntity;
import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.model.OrderStatus;
import com.laioffer.onlineorder.money.Money;
import com.laioffer.onlineorder.repository.OrderHistoryItemRepository;
import com.laioffer.onlineorder.repository.OrderPaymentRepository;
import com.laioffer.onlineorder.repository.OrderRepository;
import com.laioffer.onlineorder.statemachine.Decision;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;


/**
 * The customer's after-sales API (ADR 0002 sections 11.2 and 20): the overview of their cases and of the orders that
 * can get a request, one case with its history, opening a request, and answering a counter-offer or a rejection,
 * escalating and withdrawing. Every command takes an Idempotency-Key ({@link IdempotentRequests}) and goes through the
 * single writer ({@link AfterSalesTransitions}); another customer's order or case is 404, like a missing one.
 */
@Service
public class AfterSalesCustomerService {

    /**
     * Request every line of the order in full.
     */
    static final String WHOLE_ORDER = "WHOLE_ORDER";

    /**
     * Request the lines given, each with a quantity.
     */
    static final String ITEMS = "ITEMS";

    static final String ALREADY_OPEN = "An after-sales case is already open for this order";

    static final String NO_ATTEMPT_LEFT = "This order cannot be submitted for after-sales again";

    static final String NO_PAYMENT = "Order has no captured payment";

    static final String ZERO_AMOUNT = "Refund amount must be greater than zero";

    static final String CASE_NOT_FOUND = "After-sales case not found";

    private static final String ORDER_NOT_FOUND = "Order not found";

    /**
     * The unique key that a second request with the same attempt number of the same order runs into.
     */
    private static final String ATTEMPT_KEY = "uq_after_sales_case_attempt";

    private static final String OPEN_SCOPE_PREFIX = "after-sales-open:v1:";
    private static final String OPEN_ROUTE = "/orders/{orderId}/after-sales";

    private final AfterSalesCaseRepository caseRepository;
    private final OrderRepository orderRepository;
    private final OrderHistoryItemRepository orderHistoryItemRepository;
    private final OrderPaymentRepository orderPaymentRepository;
    private final AfterSalesTransitions transitions;
    private final IdempotentRequests idempotentRequests;
    private final AfterSalesCaseViews views;
    private final AfterSalesDeadlines deadlines;
    private final Supplier<LocalDateTime> clock;


    @Autowired
    public AfterSalesCustomerService(
            AfterSalesCaseRepository caseRepository,
            OrderRepository orderRepository,
            OrderHistoryItemRepository orderHistoryItemRepository,
            OrderPaymentRepository orderPaymentRepository,
            AfterSalesTransitions transitions,
            IdempotentRequests idempotentRequests,
            AfterSalesCaseViews views,
            AfterSalesDeadlines deadlines
    ) {
        this(caseRepository, orderRepository, orderHistoryItemRepository, orderPaymentRepository, transitions,
                idempotentRequests, views, deadlines, LocalDateTime::now);
    }


    AfterSalesCustomerService(
            AfterSalesCaseRepository caseRepository,
            OrderRepository orderRepository,
            OrderHistoryItemRepository orderHistoryItemRepository,
            OrderPaymentRepository orderPaymentRepository,
            AfterSalesTransitions transitions,
            IdempotentRequests idempotentRequests,
            AfterSalesCaseViews views,
            AfterSalesDeadlines deadlines,
            Supplier<LocalDateTime> clock
    ) {
        this.caseRepository = caseRepository;
        this.orderRepository = orderRepository;
        this.orderHistoryItemRepository = orderHistoryItemRepository;
        this.orderPaymentRepository = orderPaymentRepository;
        this.transitions = transitions;
        this.idempotentRequests = idempotentRequests;
        this.views = views;
        this.deadlines = deadlines;
        this.clock = clock;
    }


    /**
     * The customer's newest cases (without history) and the orders that can get a request now. Read-only: a replica
     * read when replicas are configured, except within the customer's read-your-writes window after a command.
     */
    @Transactional(readOnly = true)
    public AfterSalesOverviewDto getOverview(Long customerId, int limit) {
        int pageSize = Math.max(1, Math.min(limit, 100));
        List<AfterSalesCaseDto> cases = views.summaries(caseRepository.findPageByCustomerId(customerId, pageSize));
        List<OrderEntity> orders = orderRepository.findPageOpenToAfterSales(
                customerId, clock.get().minus(deadlines.requestWindow()), pageSize);
        Map<Long, Integer> requestsByOrder = new HashMap<>();
        if (!orders.isEmpty()) {
            for (AfterSalesCaseEntity request : caseRepository.findAllByOrderIdIn(
                    orders.stream().map(OrderEntity::id).toList())) {
                if (AfterSalesKind.CUSTOMER_REQUEST.name().equals(request.kind())) {
                    requestsByOrder.merge(request.orderId(), 1, Integer::sum);
                }
            }
        }
        List<EligibleOrderDto> eligibleOrders = orders.stream()
                .map(order -> new EligibleOrderDto(
                        order.id(),
                        order.orderNo(),
                        requestsByOrder.getOrDefault(order.id(), 0) + 1,
                        deadlines.requestDeadline(order.updatedAt())
                ))
                .toList();
        return new AfterSalesOverviewDto(cases, eligibleOrders);
    }


    /**
     * One of the customer's cases with its history; another customer's case is 404. Read-only.
     */
    @Transactional(readOnly = true)
    public AfterSalesCaseDto getCase(Long customerId, Long caseId) {
        AfterSalesCaseEntity current = caseRepository.findById(caseId)
                .filter(found -> found.customerId().equals(customerId))
                .orElseThrow(() -> new ResourceNotFoundException(CASE_NOT_FOUND));
        return views.detail(current);
    }


    /**
     * Opens a request on the customer's order (POST /orders/{orderId}/after-sales). Under the order's row lock, the
     * order must be COMPLETED, have a restaurant and a captured payment, and be within the request window of its
     * completion (409 AFTER_SALES_NOT_ELIGIBLE otherwise). It is the first request, or the second after the customer
     * withdrew the first (section 20). The lines must be the order's, within the quantities ordered, and add up to more
     * than zero (400); the refunds of the order cannot exceed its captured payment (409). A replay with the same key
     * answers with the case the key opened.
     */
    @Transactional
    public AfterSalesCaseDto open(Long customerId, Long orderId, OpenAfterSalesBody body, String idempotencyKey) {
        Request request = Request.of(body);
        String key = IdempotentRequests.normalizeKey(idempotencyKey);
        String requestHash = IdempotentRequests.sha256("operation=AFTER_SALES_OPEN\n"
                + "method=POST\n"
                + "route=" + OPEN_ROUTE + "\n"
                + "orderId=" + orderId + "\n"
                + "actorCustomerId=" + customerId + "\n"
                + "scope=" + request.scope() + "\n"
                + "lines=" + request.canonicalLines() + "\n"
                + "reasonCode=" + request.reason().name() + "\n"
                + "description=" + IdempotentRequests.sha256(request.description()));
        Long caseId = idempotentRequests.run(customerId, OPEN_SCOPE_PREFIX + orderId, key, requestHash, () ->
                new IdempotentRequests.Binding(orderId, openLocked(customerId, orderId, request, key).id()));
        return detail(caseId);
    }


    /**
     * Accepts the merchant's counter-offer: the case is refunded the offered amount.
     */
    @Transactional
    public AfterSalesCaseDto acceptOffer(Long customerId, Long caseId, String idempotencyKey) {
        return command(customerId, caseId, AfterSalesTrigger.CUSTOMER_ACCEPT_OFFER, "accept-offer", null,
                idempotencyKey);
    }


    /**
     * Declines the merchant's counter-offer: the case is REJECTED and the customer may escalate it.
     */
    @Transactional
    public AfterSalesCaseDto declineOffer(Long customerId, Long caseId, String idempotencyKey) {
        return command(customerId, caseId, AfterSalesTrigger.CUSTOMER_DECLINE_OFFER, "decline-offer", null,
                idempotencyKey);
    }


    /**
     * Asks the platform to step in on a rejected case, with an optional note for the history.
     */
    @Transactional
    public AfterSalesCaseDto escalate(Long customerId, Long caseId, String note, String idempotencyKey) {
        String historyNote = note == null || note.isBlank() ? null : note.trim();
        return command(customerId, caseId, AfterSalesTrigger.CUSTOMER_ESCALATE, "escalate", historyNote,
                idempotencyKey);
    }


    /**
     * Withdraws an open case. Only a withdrawn first request lets the customer request once more.
     */
    @Transactional
    public AfterSalesCaseDto withdraw(Long customerId, Long caseId, String idempotencyKey) {
        return command(customerId, caseId, AfterSalesTrigger.CUSTOMER_WITHDRAW, "withdraw", null, idempotencyKey);
    }


    /**
     * The rules of section 11.2 and 20 on the order, locked; the time is read after the lock.
     */
    private AfterSalesCaseEntity openLocked(Long customerId, Long orderId, Request request, String idempotencyKey) {
        OrderEntity order = orderRepository.lockByIdAndCustomerId(orderId, customerId);
        if (order == null) {
            throw new ResourceNotFoundException(ORDER_NOT_FOUND);
        }
        LocalDateTime now = clock.get();
        if (!OrderStatus.COMPLETED.name().equals(order.status())) {
            throw AfterSalesErrors.notEligible(
                    "Only COMPLETED orders can get after-sales; this order is " + order.status());
        }
        if (order.restaurantId() == null) {
            throw AfterSalesErrors.notEligible("This order has no restaurant");
        }
        OrderPaymentEntity payment = orderPaymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> AfterSalesErrors.notEligible(NO_PAYMENT));
        LocalDateTime windowEnd = deadlines.requestDeadline(order.updatedAt());
        if (now.isAfter(windowEnd)) {
            throw AfterSalesErrors.notEligible(
                    "The after-sales window of this order closed at " + AfterSalesErrors.timestamp(windowEnd));
        }
        List<AfterSalesCaseEntity> cases = caseRepository.findAllByOrderId(orderId);
        int attemptNo = nextAttempt(cases);

        List<AfterSalesCaseLineEntity> lines = lines(order, request);
        Money requested = Money.zero(order.currency());
        for (AfterSalesCaseLineEntity line : lines) {
            requested = requested.plus(Money.of(line.amount(), line.currency()));
        }
        if (!requested.isPositive()) {
            throw new BadRequestException(ZERO_AMOUNT);
        }
        // Only a withdrawn request (never refunded) allows another, so nothing of the order is approved yet; the cap
        // is still checked against everything approved for the order.
        Money approved = Money.zero(order.currency());
        for (AfterSalesCaseEntity other : cases) {
            if (other.approvedAmount() != null) {
                approved = approved.plus(Money.of(other.approvedAmount(), other.currency()));
            }
        }
        Money refundable = payment.captured().minus(approved);
        if (requested.isGreaterThan(refundable)) {
            throw AfterSalesErrors.notEligible("A refund of " + requested + " would exceed the " + refundable
                    + " left of the order's captured payment");
        }

        try {
            return transitions.open(new AfterSalesTransitions.CustomerRequest(
                    order,
                    attemptNo,
                    request.reason(),
                    request.description(),
                    lines,
                    requested,
                    now,
                    idempotencyKey
            ));
        } catch (DuplicateKeyException ex) {
            // Another request of the same attempt got in without the order's lock; the unique key refused this one.
            if (String.valueOf(ex.getMessage()).toLowerCase(Locale.ROOT).contains(ATTEMPT_KEY)) {
                throw AfterSalesErrors.notEligible(ALREADY_OPEN);
            }
            throw ex;
        }
    }


    /**
     * The attempt a new request of the order is: 1 without a customer request, 2 after a withdrawn first one.
     */
    private static int nextAttempt(List<AfterSalesCaseEntity> cases) {
        List<AfterSalesCaseEntity> requests = cases.stream()
                .filter(current -> AfterSalesKind.CUSTOMER_REQUEST.name().equals(current.kind()))
                .toList();
        if (requests.isEmpty()) {
            return 1;
        }
        AfterSalesCaseEntity latest = requests.get(requests.size() - 1);
        if (!AfterSalesStateMachine.DEFINITION.terminalStates().contains(AfterSalesStatus.valueOf(latest.status()))) {
            throw AfterSalesErrors.notEligible(ALREADY_OPEN);
        }
        if (requests.size() == 1 && AfterSalesCloseReason.WITHDRAWN.name().equals(latest.closeReason())) {
            return 2;
        }
        throw AfterSalesErrors.notEligible(NO_ATTEMPT_LEFT);
    }


    /**
     * The case lines of the request, priced from the order lines: every line in full for WHOLE_ORDER.
     */
    private List<AfterSalesCaseLineEntity> lines(OrderEntity order, Request request) {
        List<OrderHistoryItemEntity> items = orderHistoryItemRepository.findAllByOrderIdOrderByIdAsc(order.id());
        List<AfterSalesCaseLineEntity> lines = new ArrayList<>();
        if (request.wholeOrder()) {
            for (OrderHistoryItemEntity item : items) {
                lines.add(AfterSalesCaseLineEntity.of(null, item, item.quantity()));
            }
            return lines;
        }
        Map<Long, OrderHistoryItemEntity> itemsById = new HashMap<>();
        for (OrderHistoryItemEntity item : items) {
            itemsById.put(item.id(), item);
        }
        for (Map.Entry<Long, Integer> requested : request.quantities().entrySet()) {
            OrderHistoryItemEntity item = itemsById.get(requested.getKey());
            if (item == null) {
                throw new BadRequestException("Order item " + requested.getKey() + " is not part of this order");
            }
            if (requested.getValue() > item.quantity()) {
                throw new BadRequestException("Order item " + item.id() + " was ordered " + item.quantity()
                        + " times; at most that many can be refunded");
            }
            lines.add(AfterSalesCaseLineEntity.of(null, item, requested.getValue()));
        }
        return lines;
    }


    /**
     * A customer command on a case: idempotent per key, decided under the case's row lock by the single writer. A
     * command that already happened answers with the case unchanged; one the case's status or deadline does not allow
     * is a 409.
     *
     * @param route the last path segment of the command, which names its idempotency scope
     */
    private AfterSalesCaseDto command(
            Long customerId,
            Long caseId,
            AfterSalesTrigger trigger,
            String route,
            String note,
            String idempotencyKey
    ) {
        String key = IdempotentRequests.normalizeKey(idempotencyKey);
        String requestHash = IdempotentRequests.sha256("operation=" + trigger.name() + "\n"
                + "method=POST\n"
                + "route=/after-sales/cases/{caseId}/" + route + "\n"
                + "caseId=" + caseId + "\n"
                + "actorCustomerId=" + customerId + "\n"
                + "note=" + (note == null ? "" : IdempotentRequests.sha256(note)));
        String scope = "after-sales-" + route + ":v1:" + caseId;
        Long boundCaseId = idempotentRequests.run(customerId, scope, key, requestHash, () -> {
            AfterSalesCaseEntity locked = caseRepository.lockByIdAndCustomerId(caseId, customerId);
            if (locked == null) {
                throw new ResourceNotFoundException(CASE_NOT_FOUND);
            }
            AfterSalesTransitions.Step step = transitions.fire(locked, new AfterSalesTransitions.Command(
                    trigger, AfterSalesActor.CUSTOMER, customerId, null, note, key));
            if (step.decision() instanceof Decision.Rejected<AfterSalesStatus, AfterSalesTrigger> rejected) {
                throw AfterSalesErrors.refused(rejected, locked);
            }
            return new IdempotentRequests.Binding(locked.orderId(), locked.id());
        });
        return detail(boundCaseId);
    }


    private AfterSalesCaseDto detail(Long caseId) {
        return views.detail(caseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalStateException("After-sales case " + caseId + " is gone")));
    }


    /**
     * The checked shape of a request body (400 BAD_REQUEST): its scope, lines and reason.
     *
     * @param quantities for ITEMS, the quantity of each order line in ascending order line id; empty for WHOLE_ORDER
     */
    private record Request(String scope, Map<Long, Integer> quantities, AfterSalesReason reason, String description) {

        static Request of(OpenAfterSalesBody body) {
            List<OpenAfterSalesLine> lines = body.lines() == null ? List.of() : body.lines();
            Map<Long, Integer> quantities = new LinkedHashMap<>();
            if (WHOLE_ORDER.equals(body.scope())) {
                if (!lines.isEmpty()) {
                    throw new BadRequestException("A WHOLE_ORDER request takes no lines");
                }
            } else if (ITEMS.equals(body.scope())) {
                if (lines.isEmpty()) {
                    throw new BadRequestException("An ITEMS request needs at least one line");
                }
                lines.stream()
                        .sorted(Comparator.comparing(OpenAfterSalesLine::orderItemId))
                        .forEach(line -> {
                            if (quantities.putIfAbsent(line.orderItemId(), line.quantity()) != null) {
                                throw new BadRequestException(
                                        "Order item " + line.orderItemId() + " is listed more than once");
                            }
                        });
            } else {
                throw new BadRequestException("Scope must be WHOLE_ORDER or ITEMS");
            }
            AfterSalesReason reason;
            try {
                reason = AfterSalesReason.valueOf(body.reasonCode());
            } catch (IllegalArgumentException ex) {
                throw new BadRequestException("Unsupported reason code: " + body.reasonCode());
            }
            return new Request(body.scope(), quantities, reason, body.description());
        }


        boolean wholeOrder() {
            return quantities.isEmpty();
        }


        /**
         * "id:quantity" of every line in ascending id, joined by commas; empty for WHOLE_ORDER.
         */
        String canonicalLines() {
            List<String> lines = new ArrayList<>();
            quantities.forEach((id, quantity) -> lines.add(id + ":" + quantity));
            return String.join(",", lines);
        }
    }
}
