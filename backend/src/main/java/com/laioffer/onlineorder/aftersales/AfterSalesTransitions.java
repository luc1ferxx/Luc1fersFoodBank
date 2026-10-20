package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.entity.OrderHistoryItemEntity;
import com.laioffer.onlineorder.entity.OrderPaymentEntity;
import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.fulfilment.AfterCommit;
import com.laioffer.onlineorder.fulfilment.FulfilmentEvents;
import com.laioffer.onlineorder.messaging.AfterSalesCasePayload;
import com.laioffer.onlineorder.money.Money;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.observability.TraceIds;
import com.laioffer.onlineorder.repository.OrderPaymentRepository;
import com.laioffer.onlineorder.repository.OrderRepository;
import com.laioffer.onlineorder.service.OrderEventOutboxService;
import com.laioffer.onlineorder.statemachine.CompareAndSet;
import com.laioffer.onlineorder.statemachine.Decision;
import com.laioffer.onlineorder.statemachine.StaleTransitionException;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;


/**
 * The single writer of after-sales cases (ADR 0002 sections 5, 6, 9 and 19): the only class that inserts a case or
 * changes after_sales_cases.status. A case is opened, in its caller's transaction and under the order's row lock, by
 * a customer's request ({@link #open}) or by the cancel of a paid order ({@link #openOrderCancelled}, straight into
 * REFUNDING with its refund). A step is decided by {@link AfterSalesStateMachine} on the case the caller locked
 * ({@code FOR UPDATE}, or claimed with SKIP LOCKED), with the time read after that lock. An applied step is written
 * with a compare-and-set on the status and version it was decided on, which raises the version and sets the deadline
 * of the status entered ({@link AfterSalesDeadlines}); then come, in the same transaction, the history row with its
 * actor, the refund when the case enters REFUNDING, and the after-sales event to the outbox. A step that already
 * happened, or that the machine refuses, writes nothing; the caller maps it to its own answer
 * ({@link AfterSalesErrors#refused}).
 *
 * <p>Lock order: the case row (for an opening, the order row its caller locked, then the new case), then (entering
 * REFUNDING) a new refunds row, then the outbox sequence row of the case. The cancel of a paid order has already taken
 * the outbox sequence row of the order before it opens the case.
 */
@Service
public class AfterSalesTransitions {

    /**
     * The action of a customer's request in the history and the event.
     */
    public static final String OPEN_ACTION = "OPEN";

    /**
     * The action of the automatic refund of a cancelled paid order in the history and the event.
     */
    public static final String ORDER_CANCELLED_ACTION = "ORDER_CANCELLED";

    /**
     * A refund's gateway key is this prefix and its case id: the same key for every attempt, so the gateway refunds a
     * case once (section 9).
     */
    public static final String GATEWAY_KEY_PREFIX = "after-sales-case:";

    /**
     * The row named in a {@link StaleTransitionException}.
     */
    private static final String CASE_ROW = "after-sales case";

    /**
     * The from-status of an opening in the step metric.
     */
    private static final String NO_STATUS = "none";

    private final AfterSalesCaseRepository caseRepository;
    private final AfterSalesCaseLineRepository lineRepository;
    private final AfterSalesCaseHistoryRepository historyRepository;
    private final RefundRepository refundRepository;
    private final OrderRepository orderRepository;
    private final OrderPaymentRepository orderPaymentRepository;
    private final OrderEventOutboxService orderEventOutboxService;
    private final ApplicationMetricsService metricsService;
    private final AfterSalesDeadlines deadlines;
    private final ObjectProvider<Tracer> tracer;
    private final String topic;
    private final Supplier<LocalDateTime> clock;


    @Autowired
    public AfterSalesTransitions(
            AfterSalesCaseRepository caseRepository,
            AfterSalesCaseLineRepository lineRepository,
            AfterSalesCaseHistoryRepository historyRepository,
            RefundRepository refundRepository,
            OrderRepository orderRepository,
            OrderPaymentRepository orderPaymentRepository,
            OrderEventOutboxService orderEventOutboxService,
            ApplicationMetricsService metricsService,
            AfterSalesDeadlines deadlines,
            ObjectProvider<Tracer> tracer,
            @Value("${app.after-sales.topic:after-sales-events}") String topic
    ) {
        this(caseRepository, lineRepository, historyRepository, refundRepository, orderRepository,
                orderPaymentRepository, orderEventOutboxService, metricsService, deadlines, tracer, topic,
                LocalDateTime::now);
    }


    AfterSalesTransitions(
            AfterSalesCaseRepository caseRepository,
            AfterSalesCaseLineRepository lineRepository,
            AfterSalesCaseHistoryRepository historyRepository,
            RefundRepository refundRepository,
            OrderRepository orderRepository,
            OrderPaymentRepository orderPaymentRepository,
            OrderEventOutboxService orderEventOutboxService,
            ApplicationMetricsService metricsService,
            AfterSalesDeadlines deadlines,
            ObjectProvider<Tracer> tracer,
            String topic,
            Supplier<LocalDateTime> clock
    ) {
        this.caseRepository = caseRepository;
        this.lineRepository = lineRepository;
        this.historyRepository = historyRepository;
        this.refundRepository = refundRepository;
        this.orderRepository = orderRepository;
        this.orderPaymentRepository = orderPaymentRepository;
        this.orderEventOutboxService = orderEventOutboxService;
        this.metricsService = metricsService;
        this.deadlines = deadlines;
        this.tracer = tracer;
        this.topic = topic;
        this.clock = clock;
    }


    /**
     * Opens a customer's request in PENDING_MERCHANT with the merchant's deadline: the case, its lines, its OPEN
     * history row (CUSTOMER) and its aftersales.case_opened event. The caller checked every rule of the request under
     * the order's row lock (ADR 0002 section 11.2 and 20), {@code attemptNo} included.
     *
     * @throws org.springframework.dao.DuplicateKeyException when the order already has a request with that attempt
     *                                                       number (uq_after_sales_case_attempt): only possible for a
     *                                                       caller that did not lock the order
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AfterSalesCaseEntity open(CustomerRequest request) {
        OrderEntity order = request.order();
        LocalDateTime now = request.now();
        order.total().requireSameCurrency(request.requested());
        AfterSalesStatus status = AfterSalesStatus.PENDING_MERCHANT;
        AfterSalesCaseEntity opened = caseRepository.save(new AfterSalesCaseEntity(
                null,
                order.id(),
                order.customerId(),
                order.restaurantId(),
                AfterSalesKind.CUSTOMER_REQUEST.name(),
                request.attemptNo(),
                status.name(),
                request.reason().name(),
                request.description(),
                order.totalPrice(),
                request.requested().amount(),
                null,
                null,
                null,
                order.currency(),
                null,
                null,
                null,
                null,
                null,
                deadlines.deadlineAt(status, now),
                null,
                0,
                null,
                now,
                now
        ));
        List<AfterSalesCaseLineEntity> lines = lineRepository.saveAll(request.lines().stream()
                .map(line -> new AfterSalesCaseLineEntity(
                        null,
                        opened.id(),
                        line.orderItemId(),
                        line.menuItemName(),
                        line.unitPrice(),
                        line.quantity(),
                        line.amount(),
                        line.currency()
                ))
                .toList());
        record(opened, null, status, OPEN_ACTION, AfterSalesActor.CUSTOMER, order.customerId(), null, now);
        publish(
                opened,
                lines,
                order.orderNo(),
                FulfilmentEvents.AFTER_SALES_CASE_OPENED,
                OPEN_ACTION,
                AfterSalesActor.CUSTOMER,
                order.customerId(),
                now,
                request.idempotencyKey()
        );
        AfterCommit.run(() -> metricsService.recordAfterSalesStep(OPEN_ACTION, NO_STATUS, status.name()));
        return opened;
    }


    /**
     * Opens the automatic refund of a paid order that was cancelled (ADR 0002 D5, D12, sections 9 and 19.7): an
     * ORDER_CANCELLED case straight in REFUNDING, attempt 1, with the order total requested and approved (resolution
     * ORDER_CANCELLED) and every order line in full; its ORDER_CANCELLED history row (SYSTEM); its refund, PENDING and
     * due at once, against the order's captured payment; and its aftersales.case_opened event. The caller cancelled the
     * order in this transaction under the order's row lock, after the order's own event, and checked that the order has
     * a captured payment and a total above zero (an ORDER_CANCELLED case of 0.00 breaks chk_after_sales_case_amounts).
     *
     * @throws com.laioffer.onlineorder.money.CurrencyMismatchException when the payment was captured in another
     *                                                                  currency than the order's
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AfterSalesCaseEntity openOrderCancelled(OrderCancellation cancellation) {
        OrderEntity order = cancellation.order();
        LocalDateTime now = cancellation.cancelledAt();
        Money total = order.total();
        total.requireSameCurrency(cancellation.payment().captured());
        AfterSalesStatus status = AfterSalesStatus.REFUNDING;
        AfterSalesCaseEntity opened = caseRepository.save(new AfterSalesCaseEntity(
                null,
                order.id(),
                order.customerId(),
                order.restaurantId(),
                AfterSalesKind.ORDER_CANCELLED.name(),
                1,
                status.name(),
                null,
                null,
                order.totalPrice(),
                total.amount(),
                null,
                total.amount(),
                null,
                order.currency(),
                null,
                null,
                AfterSalesResolution.ORDER_CANCELLED.name(),
                null,
                null,
                deadlines.deadlineAt(status, now),
                null,
                0,
                null,
                now,
                now
        ));
        List<AfterSalesCaseLineEntity> lines = lineRepository.saveAll(cancellation.items().stream()
                .map(item -> AfterSalesCaseLineEntity.of(opened.id(), item, item.quantity()))
                .toList());
        record(opened, null, status, ORDER_CANCELLED_ACTION, AfterSalesActor.SYSTEM, null, null, now);
        createRefund(opened, cancellation.payment(), now);
        publish(
                opened,
                lines,
                order.orderNo(),
                FulfilmentEvents.AFTER_SALES_CASE_OPENED,
                ORDER_CANCELLED_ACTION,
                AfterSalesActor.SYSTEM,
                null,
                now,
                null
        );
        AfterCommit.run(() -> metricsService.recordAfterSalesStep(ORDER_CANCELLED_ACTION, NO_STATUS, status.name()));
        return opened;
    }


    /**
     * Takes {@code command} on a case the caller locked in this transaction. The time of the step is read here, after
     * that lock (section 19.2), so a command that waited for the lock is judged at the time it got it. When the machine
     * applies the trigger, writes the fields of table 5.2 and the deadline of the status entered with a
     * compare-and-set, then the history row, the refund (entering REFUNDING) and the event, all at that time.
     *
     * @param lockedCase the case as locked ({@code FOR UPDATE} or claimed with SKIP LOCKED) by the caller
     * @return the decision with the case as written (applied) or as locked (already done or refused)
     * @throws BadRequestException      for a counter-offer or a partial arbitration that is not more than zero and less
     *                                  than the requested amount
     * @throws StaleTransitionException when the compare-and-set does not change exactly one row (the case was not
     *                                  locked)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Step fire(AfterSalesCaseEntity lockedCase, Command command) {
        LocalDateTime now = clock.get();
        AfterSalesStatus from = AfterSalesStatus.valueOf(lockedCase.status());
        Decision<AfterSalesStatus, AfterSalesTrigger> decision = AfterSalesStateMachine.DEFINITION.decide(
                from,
                command.trigger(),
                command.actor(),
                new AfterSalesTransitionContext(lockedCase, now, command.amount())
        );
        if (!(decision instanceof Decision.Apply<AfterSalesStatus, AfterSalesTrigger> move)) {
            return new Step(decision, lockedCase);
        }
        AfterSalesStatus to = move.to();
        AfterSalesCaseEntity moved = changes(lockedCase, command)
                .applyTo(lockedCase, to, deadlines.deadlineAt(to, now), now);
        int rows = caseRepository.compareAndSetStep(
                lockedCase.id(),
                from.name(),
                lockedCase.version(),
                moved.status(),
                moved.offeredAmount(),
                moved.approvedAmount(),
                moved.refundedAmount(),
                moved.merchantDecision(),
                moved.merchantNote(),
                moved.resolution(),
                moved.closeReason(),
                moved.adminNote(),
                moved.deadlineAt(),
                now
        );
        CompareAndSet.expectOneRow(rows, CASE_ROW, lockedCase.id(), from + " at version " + lockedCase.version());
        String action = command.trigger().name();
        record(moved, from, to, action, command.actor(), command.actorId(), command.note(), now);
        if (to == AfterSalesStatus.REFUNDING) {
            createRefund(moved, capturedPayment(moved.orderId()), now);
        }
        publish(
                moved,
                lineRepository.findAllByCaseIdIn(List.of(moved.id())),
                orderRepository.findOrderNoById(moved.orderId()).orElse(null),
                to.eventType(),
                action,
                command.actor(),
                command.actorId(),
                now,
                command.idempotencyKey()
        );
        // Counted once the step commits: a later step of the caller (its idempotency record) can still roll it back.
        AfterCommit.run(() -> metricsService.recordAfterSalesStep(action, from.name(), to.name()));
        return new Step(decision, moved);
    }


    /**
     * The fields {@code command} writes (table 5.2), besides the status, its deadline and the version.
     */
    private static Changes changes(AfterSalesCaseEntity current, Command command) {
        Changes changes = new Changes(current);
        return switch (command.trigger()) {
            case MERCHANT_APPROVE -> changes.merchant(AfterSalesMerchantDecision.APPROVED, command.note())
                    .approved(current.requestedAmount(), AfterSalesResolution.MERCHANT_APPROVED);
            case MERCHANT_REJECT -> changes.merchant(AfterSalesMerchantDecision.REJECTED, requireReason(command));
            case MERCHANT_OFFER -> changes.merchant(AfterSalesMerchantDecision.OFFERED, command.note())
                    .offered(partOfRequest(current, command));
            case MERCHANT_TIMEOUT -> changes.approved(current.requestedAmount(), AfterSalesResolution.MERCHANT_TIMEOUT);
            case CUSTOMER_ACCEPT_OFFER -> changes.approved(
                    current.offeredAmount(), AfterSalesResolution.OFFER_ACCEPTED);
            case OFFER_TIMEOUT -> changes.approved(current.offeredAmount(), AfterSalesResolution.OFFER_TIMEOUT);
            // A declined offer and an escalation only move the status; an escalation's note goes to the history.
            case CUSTOMER_DECLINE_OFFER, CUSTOMER_ESCALATE -> changes;
            case CUSTOMER_TIMEOUT -> changes.closed(AfterSalesCloseReason.CUSTOMER_TIMEOUT);
            case CUSTOMER_WITHDRAW -> changes.closed(AfterSalesCloseReason.WITHDRAWN);
            case ADMIN_APPROVE_FULL -> changes.adminNote(command.note())
                    .approved(current.requestedAmount(), AfterSalesResolution.ARBITRATION_FULL);
            case ADMIN_APPROVE_PARTIAL -> changes.adminNote(command.note())
                    .approved(partOfRequest(current, command), AfterSalesResolution.ARBITRATION_PARTIAL);
            case ADMIN_REJECT -> changes.adminNote(command.note()).closed(AfterSalesCloseReason.ARBITRATION_REJECTED);
            case ARBITRATION_TIMEOUT -> changes.approved(
                    current.requestedAmount(), AfterSalesResolution.ARBITRATION_TIMEOUT);
            case REFUND_SUCCEEDED -> changes.refunded(current.approvedAmount());
        };
    }


    /**
     * The amount of a counter-offer or a partial arbitration: more than zero and less than the requested amount
     * (section 5.2), in the case's currency.
     */
    private static BigDecimal partOfRequest(AfterSalesCaseEntity current, Command command) {
        if (command.amount() == null) {
            throw new IllegalArgumentException(command.trigger() + " needs an amount");
        }
        Money requested = Money.of(current.requestedAmount(), current.currency());
        if (!command.amount().isPositive() || !command.amount().isLessThan(requested)) {
            throw new BadRequestException("The amount must be more than 0 and less than the requested " + requested);
        }
        return command.amount().amount();
    }


    private static String requireReason(Command command) {
        if (command.note() == null || command.note().isBlank()) {
            throw new IllegalArgumentException("A merchant rejection needs a reason");
        }
        return command.note();
    }


    /**
     * The amount a step set, for its history row: the requested amount of an opening, the offer, the approved amount
     * entering REFUNDING and the refunded amount; none for the other statuses.
     */
    private static BigDecimal stepAmount(AfterSalesStatus to, AfterSalesCaseEntity current) {
        return switch (to) {
            case PENDING_MERCHANT -> current.requestedAmount();
            case COUNTER_OFFERED -> current.offeredAmount();
            case REFUNDING -> current.approvedAmount();
            case REFUNDED -> current.refundedAmount();
            case REJECTED, IN_ARBITRATION, CLOSED -> null;
        };
    }


    private void record(
            AfterSalesCaseEntity current,
            AfterSalesStatus from,
            AfterSalesStatus to,
            String action,
            AfterSalesActor actor,
            Long actorId,
            String note,
            LocalDateTime occurredAt
    ) {
        historyRepository.save(new AfterSalesCaseHistoryEntity(
                null,
                current.id(),
                from == null ? null : from.name(),
                to.name(),
                action,
                actor.name(),
                actorId,
                stepAmount(to, current),
                current.currency(),
                note,
                TraceIds.current(tracer.getIfAvailable()),
                occurredAt
        ));
    }


    private OrderPaymentEntity capturedPayment(Long orderId) {
        return orderPaymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException("Order " + orderId + " has no captured payment to refund"));
    }


    /**
     * The refund of a case that entered REFUNDING (section 9): PENDING and due now, for the approved amount of the
     * order's captured payment. The composite key fk_refund_case_approved ties its amount and currency to the case.
     */
    private void createRefund(AfterSalesCaseEntity refunding, OrderPaymentEntity payment, LocalDateTime now) {
        refundRepository.save(new RefundEntity(
                null,
                refunding.id(),
                refunding.orderId(),
                refunding.customerId(),
                GATEWAY_KEY_PREFIX + refunding.id(),
                payment.providerReference(),
                refunding.approvedAmount(),
                refunding.currency(),
                RefundStatus.PENDING.name(),
                0,
                now,
                null,
                null,
                null,
                null,
                now,
                now,
                null
        ));
    }


    private void publish(
            AfterSalesCaseEntity current,
            List<AfterSalesCaseLineEntity> lines,
            Long orderNo,
            String eventType,
            String action,
            AfterSalesActor actor,
            Long actorId,
            LocalDateTime occurredAt,
            String idempotencyKey
    ) {
        AfterSalesCasePayload data = AfterSalesEvents.payload(current, lines, orderNo, action, actor, actorId);
        orderEventOutboxService.enqueueEvent(
                topic,
                String.valueOf(current.orderId()),
                AfterSalesEvents.envelope(eventType, data, occurredAt, idempotencyKey)
        );
    }


    /**
     * A customer's request on a completed order, which the caller checked under the order's row lock.
     *
     * @param order          the order, locked
     * @param attemptNo      1, or 2 after a withdrawn first request
     * @param lines          the lines asked for, without an id or a case
     * @param requested      the sum of the lines, more than zero, in the order's currency
     * @param now            the time of the request, read after the order's lock
     * @param idempotencyKey the request's Idempotency-Key
     */
    public record CustomerRequest(
            OrderEntity order,
            int attemptNo,
            AfterSalesReason reason,
            String description,
            List<AfterSalesCaseLineEntity> lines,
            Money requested,
            LocalDateTime now,
            String idempotencyKey
    ) {

        public CustomerRequest {
            Objects.requireNonNull(order, "order");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(description, "description");
            lines = List.copyOf(lines);
            Objects.requireNonNull(requested, "requested");
            Objects.requireNonNull(now, "now");
        }
    }


    /**
     * A paid order the caller cancelled in this transaction, to refund in full.
     *
     * @param order       the order as the cancel wrote it, still locked
     * @param payment     the order's captured payment, which the refund names
     * @param items       every line of the order; the case asks for each in full
     * @param cancelledAt the time of the cancel, read after the order's lock: the time of the opening and when the
     *                    refund is due
     */
    public record OrderCancellation(
            OrderEntity order,
            OrderPaymentEntity payment,
            List<OrderHistoryItemEntity> items,
            LocalDateTime cancelledAt
    ) {

        public OrderCancellation {
            Objects.requireNonNull(order, "order");
            Objects.requireNonNull(payment, "payment");
            if (!order.id().equals(payment.orderId())) {
                throw new IllegalArgumentException(
                        "Payment " + payment.id() + " belongs to order " + payment.orderId() + ", not " + order.id());
            }
            items = List.copyOf(items);
            Objects.requireNonNull(cancelledAt, "cancelledAt");
        }
    }


    /**
     * A step to take on a case.
     *
     * @param actorId        the customers.id of the customer, merchant or admin; null for SYSTEM
     * @param amount         the amount of a counter-offer or a partial arbitration, in the case's currency; null for
     *                       every other trigger
     * @param note           the merchant's reason or note, the customer's escalation note or the admin's note, kept in
     *                       the history (and on the case for the merchant and the admin)
     * @param idempotencyKey the Idempotency-Key of the request that takes the step, if any: the event's correlation id
     */
    public record Command(
            AfterSalesTrigger trigger,
            AfterSalesActor actor,
            Long actorId,
            Money amount,
            String note,
            String idempotencyKey
    ) {

        public Command {
            Objects.requireNonNull(trigger, "trigger");
            Objects.requireNonNull(actor, "actor");
        }
    }


    /**
     * @param decision what the machine decided
     * @param current  the case as written when the step was applied; the locked case, unchanged, otherwise
     */
    public record Step(Decision<AfterSalesStatus, AfterSalesTrigger> decision, AfterSalesCaseEntity current) {

        public boolean applied() {
            return decision instanceof Decision.Apply;
        }
    }


    /**
     * The columns a step may write besides the status, the deadline and the version, starting from the locked case.
     */
    private static final class Changes {

        private String merchantDecision;
        private String merchantNote;
        private BigDecimal offeredAmount;
        private BigDecimal approvedAmount;
        private BigDecimal refundedAmount;
        private String resolution;
        private String closeReason;
        private String adminNote;


        Changes(AfterSalesCaseEntity current) {
            this.merchantDecision = current.merchantDecision();
            this.merchantNote = current.merchantNote();
            this.offeredAmount = current.offeredAmount();
            this.approvedAmount = current.approvedAmount();
            this.refundedAmount = current.refundedAmount();
            this.resolution = current.resolution();
            this.closeReason = current.closeReason();
            this.adminNote = current.adminNote();
        }


        Changes merchant(AfterSalesMerchantDecision decision, String note) {
            this.merchantDecision = decision.name();
            this.merchantNote = note;
            return this;
        }


        Changes offered(BigDecimal amount) {
            this.offeredAmount = amount;
            return this;
        }


        Changes approved(BigDecimal amount, AfterSalesResolution resolution) {
            this.approvedAmount = amount;
            this.resolution = resolution.name();
            return this;
        }


        Changes refunded(BigDecimal amount) {
            this.refundedAmount = amount;
            return this;
        }


        Changes closed(AfterSalesCloseReason reason) {
            this.closeReason = reason.name();
            return this;
        }


        Changes adminNote(String note) {
            this.adminNote = note;
            return this;
        }


        /**
         * The case after the step: in {@code to} with its deadline, one version later, changed at {@code now}.
         */
        AfterSalesCaseEntity applyTo(
                AfterSalesCaseEntity current,
                AfterSalesStatus to,
                LocalDateTime deadlineAt,
                LocalDateTime now
        ) {
            return new AfterSalesCaseEntity(
                    current.id(),
                    current.orderId(),
                    current.customerId(),
                    current.restaurantId(),
                    current.kind(),
                    current.attemptNo(),
                    to.name(),
                    current.reasonCode(),
                    current.description(),
                    current.orderTotal(),
                    current.requestedAmount(),
                    offeredAmount,
                    approvedAmount,
                    refundedAmount,
                    current.currency(),
                    merchantDecision,
                    merchantNote,
                    resolution,
                    closeReason,
                    adminNote,
                    deadlineAt,
                    null,
                    0,
                    current.version() + 1,
                    current.createdAt(),
                    now
            );
        }
    }
}
