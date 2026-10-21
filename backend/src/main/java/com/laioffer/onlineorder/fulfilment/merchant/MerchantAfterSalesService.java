package com.laioffer.onlineorder.fulfilment.merchant;


import com.laioffer.onlineorder.datasource.ReadOnlyTransactions;
import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.fulfilment.AfterCommit;
import com.laioffer.onlineorder.model.OrderActor;
import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands;
import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands.AfterSalesDecision;
import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands.AfterSalesResponse;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;


/**
 * The merchant's after-sales inbox and answers (ADR 0002 sections 5.5 and 11.3): the customers' requests on the orders
 * of their restaurant, and the one answer to each, approve, reject with a reason, or a partial refund.
 *
 * <p>Every request first checks the merchant's account (403 when it is not bound to a restaurant or deactivated),
 * outside any read-only transaction. A command is then scoped to the merchant's restaurant twice, like the merchant's
 * order commands: the inbox row is locked by case id and restaurant id (404 when the inbox has no such case), and the
 * order module locks the case scoped to the restaurant again ({@link OrderLifecycleCommands#decideAfterSalesCase}).
 * The case's step, its outbox event and the inbox row, brought to the case the command returns, commit in one
 * transaction; a refused answer (409) changes neither.
 *
 * <p>The caller passes the merchant's customer id, resolved from the login by the web layer; the module only reads its
 * own tables.
 */
@Service
public class MerchantAfterSalesService {

    static final String CASE_NOT_FOUND = "After-sales case not found";

    /**
     * The statuses of after_sales_cases (ADR 0002 section 5.1) the list filters by.
     */
    static final Set<String> STATUSES = Set.of(
            "PENDING_MERCHANT",
            "COUNTER_OFFERED",
            "REJECTED",
            "IN_ARBITRATION",
            "REFUNDING",
            "REFUNDED",
            "CLOSED"
    );

    private static final Logger LOGGER = LoggerFactory.getLogger(MerchantAfterSalesService.class);

    private final MerchantAccountRepository merchantAccountRepository;
    private final MerchantAfterSalesRepository merchantAfterSalesRepository;
    private final MerchantAfterSalesProjection projection;
    private final OrderLifecycleCommands orderLifecycleCommands;
    private final ApplicationMetricsService metricsService;
    private final ReadOnlyTransactions readOnlyTransactions;


    public MerchantAfterSalesService(
            MerchantAccountRepository merchantAccountRepository,
            MerchantAfterSalesRepository merchantAfterSalesRepository,
            MerchantAfterSalesProjection projection,
            OrderLifecycleCommands orderLifecycleCommands,
            ApplicationMetricsService metricsService,
            ReadOnlyTransactions readOnlyTransactions
    ) {
        this.merchantAccountRepository = merchantAccountRepository;
        this.merchantAfterSalesRepository = merchantAfterSalesRepository;
        this.projection = projection;
        this.orderLifecycleCommands = orderLifecycleCommands;
        this.metricsService = metricsService;
        this.readOnlyTransactions = readOnlyTransactions;
    }


    /**
     * The newest requests on the merchant's restaurant, optionally of one status. The account check runs first,
     * outside any transaction, so it reads the primary and a deactivation takes effect on the next request whatever
     * the replica lag; the page itself is a read-only transaction (a replica read when replicas are configured).
     */
    public List<MerchantAfterSalesCaseDto> getCases(Long merchantCustomerId, String status, int limit) {
        Long restaurantId = MerchantAccounts.requireActiveRestaurantId(merchantAccountRepository, merchantCustomerId);
        int normalizedLimit = Math.max(1, Math.min(limit, 100));
        String normalizedStatus = normalizeStatus(status);
        List<MerchantAfterSalesEntity> cases = readOnlyTransactions.read("MerchantAfterSalesService.getCases", () ->
                normalizedStatus == null
                        ? merchantAfterSalesRepository.findPageByRestaurantId(restaurantId, normalizedLimit)
                        : merchantAfterSalesRepository.findPageByRestaurantIdAndStatus(
                                restaurantId,
                                normalizedStatus,
                                normalizedLimit
                        ));
        return cases.stream().map(projection::toDto).toList();
    }


    /**
     * Approves the requested amount: the case is refunded.
     */
    @Transactional
    public MerchantAfterSalesCaseDto approve(Long merchantCustomerId, Long caseId) {
        return answer(merchantCustomerId, caseId, AfterSalesResponse.approve(), "approved");
    }


    /**
     * Rejects the request with a reason the customer sees; the customer may ask the platform to step in.
     */
    @Transactional
    public MerchantAfterSalesCaseDto reject(Long merchantCustomerId, Long caseId, String reason) {
        return answer(merchantCustomerId, caseId, AfterSalesResponse.reject(reason), "rejected");
    }


    /**
     * Offers a partial refund, more than zero and less than the requested amount; the customer accepts or declines it.
     */
    @Transactional
    public MerchantAfterSalesCaseDto offer(Long merchantCustomerId, Long caseId, BigDecimal amount, String note) {
        return answer(merchantCustomerId, caseId, AfterSalesResponse.offer(amount, note), "offered");
    }


    private MerchantAfterSalesCaseDto answer(
            Long merchantCustomerId,
            Long caseId,
            AfterSalesResponse response,
            String decision
    ) {
        Long restaurantId = MerchantAccounts.requireActiveRestaurantId(merchantAccountRepository, merchantCustomerId);
        MerchantAfterSalesEntity row = merchantAfterSalesRepository.lockByCaseIdAndRestaurantId(caseId, restaurantId);
        if (row == null) {
            throw new ResourceNotFoundException(CASE_NOT_FOUND);
        }

        AfterSalesDecision answered = orderLifecycleCommands.decideAfterSalesCase(
                caseId,
                restaurantId,
                response,
                OrderActor.merchant(merchantCustomerId)
        );
        projection.apply(answered.snapshot(), LocalDateTime.now());

        // Count only an answer this command gave, not a retry of it.
        if (answered.changed()) {
            AfterCommit.run(() -> metricsService.recordAfterSalesMerchantDecision(decision));
        }
        LOGGER.info(
                "Merchant after-sales command: restaurant_id={}, case_id={}, answer={}, from={}, status={}, changed={}",
                restaurantId,
                caseId,
                response.type(),
                row.status(),
                answered.snapshot().status(),
                answered.changed()
        );
        return projection.toDto(merchantAfterSalesRepository.findById(row.id())
                .orElseThrow(() -> new IllegalStateException("Merchant after-sales row " + row.id() + " is gone")));
    }


    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(normalized)) {
            throw new BadRequestException("Unsupported after-sales status: " + status);
        }
        return normalized;
    }
}
