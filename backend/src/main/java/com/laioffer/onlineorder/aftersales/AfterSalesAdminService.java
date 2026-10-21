package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.audit.AuditActions;
import com.laioffer.onlineorder.audit.AuditActor;
import com.laioffer.onlineorder.audit.AuditLogService;
import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.money.Money;
import com.laioffer.onlineorder.repository.OrderRepository;
import com.laioffer.onlineorder.statemachine.Decision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;


/**
 * The platform's after-sales tools (ADR 0002 D10, D11, sections 5.2, 5.4, 9 and 11.4): the queue of cases by status
 * and one case with its whole history, the arbitration of an escalated case, and the refunds by status with the retry
 * of a FAILED one. Admin-only: AppConfig matches /admin/after-sales and /admin/refunds to ROLE_ADMIN. The staff see
 * every case and the refunds' last_error, which customers never do.
 *
 * <p>An arbitration takes an Idempotency-Key ({@link IdempotentRequests}, scope
 * {@code after-sales-arbitrate:v1:{caseId}} under the admin's login) and fires ADMIN_APPROVE_FULL,
 * ADMIN_APPROVE_PARTIAL or ADMIN_REJECT through the single writer ({@link AfterSalesTransitions}) with the ADMIN actor,
 * on the case locked {@code FOR UPDATE}. Arbitrating has no deadline guard (section 19.1): until the poller fires
 * ARBITRATION_TIMEOUT, and against a customer's withdrawal, whoever locks the case first wins.
 *
 * <p>A retry moves a FAILED refund back to PENDING, due at once with its attempts starting over, as decided by the
 * refund machine (ADMIN_RETRY) on the refund locked {@code FOR UPDATE}. The executor holds no FAILED refund (giving up
 * is its last write of an attempt), its claim skips locked rows, and its record transaction drops a result whose refund
 * moved on, so the retry never overlaps an attempt; the gateway answers the same key with the same refund should an
 * earlier attempt have refunded after all.
 *
 * <p>Each decision or retry that changed something writes one audit_log entry in its transaction
 * (after_sales_case.arbitrate, refund.retry); a replay, a refused command and a retry of a refund already PENDING write
 * none (D11).
 */
@Service
public class AfterSalesAdminService {

    /**
     * Refund the requested amount.
     */
    static final String FULL = "FULL";

    /**
     * Refund the amount given, less than the requested amount.
     */
    static final String PARTIAL = "PARTIAL";

    /**
     * Close the case without a refund.
     */
    static final String REJECT = "REJECT";

    /**
     * The largest page of a list.
     */
    static final int MAX_LIMIT = 100;

    static final String REFUND_NOT_FOUND = "Refund not found";

    private static final String ARBITRATE_SCOPE_PREFIX = "after-sales-arbitrate:v1:";
    private static final String ARBITRATE_ROUTE = "/admin/after-sales/{caseId}/arbitrate";

    private static final Logger LOGGER = LoggerFactory.getLogger(AfterSalesAdminService.class);

    private final AfterSalesCaseRepository caseRepository;
    private final RefundRepository refundRepository;
    private final OrderRepository orderRepository;
    private final AfterSalesTransitions transitions;
    private final IdempotentRequests idempotentRequests;
    private final AfterSalesCaseViews views;
    private final AuditLogService auditLogService;
    private final Supplier<LocalDateTime> clock;


    @Autowired
    public AfterSalesAdminService(
            AfterSalesCaseRepository caseRepository,
            RefundRepository refundRepository,
            OrderRepository orderRepository,
            AfterSalesTransitions transitions,
            IdempotentRequests idempotentRequests,
            AfterSalesCaseViews views,
            AuditLogService auditLogService
    ) {
        this(caseRepository, refundRepository, orderRepository, transitions, idempotentRequests, views,
                auditLogService, LocalDateTime::now);
    }


    AfterSalesAdminService(
            AfterSalesCaseRepository caseRepository,
            RefundRepository refundRepository,
            OrderRepository orderRepository,
            AfterSalesTransitions transitions,
            IdempotentRequests idempotentRequests,
            AfterSalesCaseViews views,
            AuditLogService auditLogService,
            Supplier<LocalDateTime> clock
    ) {
        this.caseRepository = caseRepository;
        this.refundRepository = refundRepository;
        this.orderRepository = orderRepository;
        this.transitions = transitions;
        this.idempotentRequests = idempotentRequests;
        this.views = views;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }


    /**
     * The cases in {@code status} (the queue defaults to IN_ARBITRATION), without their history. An open status lists
     * the nearest deadline first, the others the newest case first. Read-only.
     *
     * @param status one of {@link AfterSalesStatus}, in any case; 400 otherwise
     * @param limit  the page size, kept between 1 and {@value #MAX_LIMIT}
     */
    @Transactional(readOnly = true)
    public List<AfterSalesCaseDto> getCases(String status, int limit) {
        AfterSalesStatus wanted = caseStatus(status);
        int pageSize = pageSize(limit);
        List<AfterSalesCaseEntity> cases = wanted.isOpen()
                ? caseRepository.findQueueByStatus(wanted.name(), pageSize)
                : caseRepository.findPageByStatus(wanted.name(), pageSize);
        return views.adminSummaries(cases);
    }


    /**
     * Any case with its whole history and its refund, last_error included. Read-only.
     */
    @Transactional(readOnly = true)
    public AfterSalesCaseDto getCase(Long caseId) {
        return views.adminDetail(caseRepository.findById(caseId)
                .orElseThrow(() -> new ResourceNotFoundException(AfterSalesCustomerService.CASE_NOT_FOUND)));
    }


    /**
     * Decides an escalated case (POST /admin/after-sales/{caseId}/arbitrate): FULL refunds the requested amount,
     * PARTIAL the amount given (more than zero and less than the requested amount), REJECT closes the case; the note
     * is kept on the case and in its history. The shape of the body is checked first (400); then, under the case's
     * row lock, the case must be IN_ARBITRATION (409 AFTER_SALES_STATUS_CONFLICT), whatever its deadline. A replay
     * with the same key answers with the case as it is now and changes nothing.
     *
     * @param actor the admin, whose login owns the idempotency key and is the case history's actor
     */
    @Transactional
    public AfterSalesCaseDto arbitrate(
            Long caseId,
            ArbitrateAfterSalesBody body,
            String idempotencyKey,
            AuditActor actor
    ) {
        Long adminId = Objects.requireNonNull(actor.customerId(), "An arbitration needs the admin's login");
        Arbitration arbitration = Arbitration.of(body);
        String key = IdempotentRequests.normalizeKey(idempotencyKey);
        String requestHash = IdempotentRequests.sha256("operation=AFTER_SALES_ARBITRATE\n"
                + "method=POST\n"
                + "route=" + ARBITRATE_ROUTE + "\n"
                + "caseId=" + caseId + "\n"
                + "actorCustomerId=" + adminId + "\n"
                + "decision=" + arbitration.decision() + "\n"
                + "amount=" + arbitration.canonicalAmount() + "\n"
                + "note=" + IdempotentRequests.sha256(arbitration.note()));
        Long boundCaseId = idempotentRequests.run(adminId, ARBITRATE_SCOPE_PREFIX + caseId, key, requestHash, () -> {
            AfterSalesCaseEntity locked = caseRepository.lockById(caseId);
            if (locked == null) {
                throw new ResourceNotFoundException(AfterSalesCustomerService.CASE_NOT_FOUND);
            }
            AfterSalesTransitions.Step step = transitions.fire(locked, new AfterSalesTransitions.Command(
                    arbitration.trigger(),
                    AfterSalesActor.ADMIN,
                    adminId,
                    arbitration.amountIn(locked),
                    arbitration.note(),
                    key
            ));
            if (step.decision() instanceof Decision.Rejected<AfterSalesStatus, AfterSalesTrigger> rejected) {
                throw AfterSalesErrors.refused(rejected, locked);
            }
            if (step.decision() instanceof Decision.Apply<AfterSalesStatus, AfterSalesTrigger>) {
                auditArbitration(actor, arbitration, locked, step.current(), key);
            }
            return new IdempotentRequests.Binding(locked.orderId(), locked.id());
        });
        return views.adminDetail(caseRepository.findById(boundCaseId)
                .orElseThrow(() -> new IllegalStateException("After-sales case " + boundCaseId + " is gone")));
    }


    /**
     * The refunds in {@code status} (the list defaults to FAILED), the newest first, last_error included. Read-only.
     *
     * @param status one of {@link RefundStatus}, in any case; 400 otherwise
     * @param limit  the page size, kept between 1 and {@value #MAX_LIMIT}
     */
    @Transactional(readOnly = true)
    public List<RefundDto> getRefunds(String status, int limit) {
        RefundStatus wanted = refundStatus(status);
        List<RefundEntity> refunds = refundRepository.findPageByStatus(wanted.name(), pageSize(limit));
        if (refunds.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> orderNumbers = new HashMap<>();
        for (OrderEntity order : orderRepository.findAllById(
                refunds.stream().map(RefundEntity::orderId).distinct().toList())) {
            orderNumbers.put(order.id(), order.orderNo());
        }
        return refunds.stream()
                .map(refund -> new RefundDto(refund, orderNumbers.get(refund.orderId())))
                .toList();
    }


    /**
     * Retries a FAILED refund (POST /admin/refunds/{refundId}/retry): under its row lock it becomes PENDING, due now,
     * with no attempt made, and the executor calls the gateway again with the same key. A refund already PENDING (a
     * repeated retry) is answered unchanged; any other status is 409 REFUND_STATUS_CONFLICT. Only a refund that moved
     * is audited, with the attempts and the error the retry leaves behind.
     */
    @Transactional
    public RefundDto retryRefund(Long refundId, AuditActor actor) {
        RefundEntity locked = refundRepository.lockById(refundId);
        if (locked == null) {
            throw new ResourceNotFoundException(REFUND_NOT_FOUND);
        }
        Decision<RefundStatus, RefundTrigger> decision = RefundStateMachine.DEFINITION.decide(
                RefundStatus.valueOf(locked.status()), RefundTrigger.ADMIN_RETRY, AfterSalesActor.ADMIN, null);
        if (decision instanceof Decision.Rejected<RefundStatus, RefundTrigger> rejected) {
            throw AfterSalesErrors.refundRetryRefused(rejected);
        }
        RefundEntity current = locked;
        if (decision instanceof Decision.Apply<RefundStatus, RefundTrigger> retry) {
            // Read after the lock: the refund is due from the moment the retry got it.
            LocalDateTime now = clock.get();
            current = refundRepository.save(retried(locked, retry.to(), now));
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("case_id", locked.caseId());
            details.put("order_id", locked.orderId());
            details.put("previous_attempts", locked.attempts());
            details.put("last_error", locked.lastError());
            auditLogService.record(actor, AuditActions.REFUND_RETRY, AuditActions.TARGET_REFUND, refundId, details);
            LOGGER.info("Refund {} of case {} (order {}) retried by admin {} after {} attempt(s): {}", locked.id(),
                    locked.caseId(), locked.orderId(), actor.customerId(), locked.attempts(), locked.lastError());
        }
        return new RefundDto(current, orderRepository.findOrderNoById(current.orderId()).orElse(null));
    }


    /**
     * The after_sales_case.arbitrate entry of an applied decision: the decision, the amount it approved (none for a
     * rejection), the move and the key. The note stays on the case and in its history.
     */
    private void auditArbitration(
            AuditActor actor,
            Arbitration arbitration,
            AfterSalesCaseEntity before,
            AfterSalesCaseEntity after,
            String idempotencyKey
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("order_id", before.orderId());
        details.put("decision", arbitration.decision());
        if (after.approvedAmount() != null) {
            details.put("amount", Money.of(after.approvedAmount(), after.currency()).amount());
            details.put("currency", after.currency());
        }
        details.put("from_status", before.status());
        details.put("to_status", after.status());
        details.put("idempotency_key", idempotencyKey);
        auditLogService.record(actor, AuditActions.AFTER_SALES_CASE_ARBITRATE, AuditActions.TARGET_AFTER_SALES_CASE,
                before.id(), details);
    }


    /**
     * The refund an ADMIN_RETRY leaves: in {@code to} (PENDING) and due at {@code now}, with no attempt made and no
     * lease. The last error stays until the next attempt replaces or clears it.
     */
    private static RefundEntity retried(RefundEntity failed, RefundStatus to, LocalDateTime now) {
        return new RefundEntity(
                failed.id(),
                failed.caseId(),
                failed.orderId(),
                failed.customerId(),
                failed.gatewayKey(),
                failed.paymentReference(),
                failed.amount(),
                failed.currency(),
                to.name(),
                0,
                now,
                null,
                null,
                failed.lastError(),
                failed.version(),
                failed.createdAt(),
                now,
                null
        );
    }


    private static AfterSalesStatus caseStatus(String status) {
        try {
            return AfterSalesStatus.valueOf(normalizeStatus(status));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unsupported after-sales status: " + status);
        }
    }


    private static RefundStatus refundStatus(String status) {
        try {
            return RefundStatus.valueOf(normalizeStatus(status));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unsupported refund status: " + status);
        }
    }


    /** Trimmed and upper-cased like the merchant's filter, so {@code closed} lists the CLOSED ones. */
    private static String normalizeStatus(String status) {
        return String.valueOf(status).trim().toUpperCase(Locale.ROOT);
    }


    private static int pageSize(int limit) {
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }


    /**
     * The checked shape of an arbitration (400 BAD_REQUEST): its decision, the amount only a PARTIAL decision takes,
     * and its note, trimmed.
     */
    private record Arbitration(String decision, AfterSalesTrigger trigger, BigDecimal amount, String note) {

        static Arbitration of(ArbitrateAfterSalesBody body) {
            String decision = String.valueOf(body.decision());
            AfterSalesTrigger trigger = switch (decision) {
                case FULL -> AfterSalesTrigger.ADMIN_APPROVE_FULL;
                case PARTIAL -> AfterSalesTrigger.ADMIN_APPROVE_PARTIAL;
                case REJECT -> AfterSalesTrigger.ADMIN_REJECT;
                default -> throw new BadRequestException("Decision must be FULL, PARTIAL or REJECT");
            };
            if (PARTIAL.equals(decision) && body.amount() == null) {
                throw new BadRequestException("A PARTIAL decision needs an amount");
            }
            if (!PARTIAL.equals(decision) && body.amount() != null) {
                throw new BadRequestException("A " + decision + " decision takes no amount");
            }
            if (body.note() == null || body.note().isBlank()) {
                throw new BadRequestException("An arbitration needs a note");
            }
            return new Arbitration(decision, trigger, body.amount(), body.note().trim());
        }


        /**
         * The amount as the request hash names it: by value, so 5.5 and 5.50 are the same request; empty without one.
         */
        String canonicalAmount() {
            return amount == null ? "" : amount.stripTrailingZeros().toPlainString();
        }


        /**
         * The amount in the case's currency, or null without one. The single writer checks that it is more than zero
         * and less than the requested amount once the case may be arbitrated.
         *
         * @throws BadRequestException for more decimal places than the currency has (section 19.5), or for an amount
         *                             beyond what a money column holds, which no request reaches
         */
        Money amountIn(AfterSalesCaseEntity current) {
            if (amount == null) {
                return null;
            }
            int digits = Money.currency(current.currency()).getDefaultFractionDigits();
            if (amount.stripTrailingZeros().scale() > digits) {
                throw new BadRequestException("Amount must have at most " + digits + " decimal places");
            }
            try {
                return Money.of(amount, current.currency());
            } catch (ArithmeticException ex) {
                throw new BadRequestException("The amount must be more than 0 and less than the requested "
                        + Money.of(current.requestedAmount(), current.currency()));
            }
        }
    }
}
