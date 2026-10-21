package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.fulfilment.order.OrderActor;
import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands.AfterSalesDecision;
import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands.AfterSalesResponse;
import com.laioffer.onlineorder.money.Money;
import com.laioffer.onlineorder.repository.OrderRepository;
import com.laioffer.onlineorder.service.AfterSalesMerchantCommands;
import com.laioffer.onlineorder.statemachine.Decision;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;


/**
 * Takes the merchant's answer to a customer's request (ADR 0002 sections 5.2, 7 and 11.3) for the order module, which
 * forwards {@code OrderLifecycleCommands.decideAfterSalesCase} to {@link AfterSalesMerchantCommands}. The case is
 * locked scoped to the merchant's restaurant; the single writer ({@link AfterSalesTransitions#fire}) decides
 * MERCHANT_APPROVE, MERCHANT_REJECT or MERCHANT_OFFER with the after-sales machine and writes the step. The automatic
 * refund of a cancelled order (ORDER_CANCELLED) is nobody's to answer, so it does not exist for the merchant, like a
 * case of another restaurant.
 */
@Service
public class AfterSalesMerchantDecisions implements AfterSalesMerchantCommands {

    static final String REASON_REQUIRED = "A rejection needs a reason";

    static final String AMOUNT_REQUIRED = "A counter-offer needs an amount";

    static final String AMOUNT_NOT_EXPECTED = "Only a counter-offer takes an amount";

    private final AfterSalesCaseRepository caseRepository;
    private final AfterSalesCaseLineRepository lineRepository;
    private final OrderRepository orderRepository;
    private final AfterSalesTransitions transitions;


    public AfterSalesMerchantDecisions(
            AfterSalesCaseRepository caseRepository,
            AfterSalesCaseLineRepository lineRepository,
            OrderRepository orderRepository,
            AfterSalesTransitions transitions
    ) {
        this.caseRepository = caseRepository;
        this.lineRepository = lineRepository;
        this.orderRepository = orderRepository;
        this.transitions = transitions;
    }


    /**
     * Checks the shape of the answer, locks the case of the restaurant and fires the merchant's trigger on it. A repeat
     * of an answer the merchant gave already is answered with the case unchanged; one the case's status or deadline
     * refuses is the 409 of {@link AfterSalesErrors#refused}, and the caller's transaction rolls back.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AfterSalesDecision decide(Long caseId, Long restaurantId, AfterSalesResponse response, OrderActor actor) {
        AfterSalesTrigger trigger = trigger(response);
        String note = note(response);
        AfterSalesCaseEntity locked = restaurantId == null
                ? null
                : caseRepository.lockByIdAndRestaurantId(caseId, restaurantId);
        if (locked == null || !AfterSalesKind.CUSTOMER_REQUEST.name().equals(locked.kind())) {
            throw new ResourceNotFoundException(AfterSalesCustomerService.CASE_NOT_FOUND);
        }
        AfterSalesActor actorType = actorType(actor);
        Money amount = response.offerAmount() == null ? null : offerAmount(response.offerAmount(), locked);
        AfterSalesTransitions.Step step = transitions.fire(
                locked, new AfterSalesTransitions.Command(trigger, actorType, actor.id(), amount, note, null));
        if (step.decision() instanceof Decision.Rejected<AfterSalesStatus, AfterSalesTrigger> rejected) {
            throw AfterSalesErrors.refused(rejected, locked);
        }
        AfterSalesCaseEntity current = step.current();
        return new AfterSalesDecision(
                AfterSalesEvents.payload(
                        current,
                        lineRepository.findAllByCaseIdIn(List.of(current.id())),
                        orderRepository.findOrderNoById(current.orderId()).orElse(null),
                        trigger.name(),
                        actorType,
                        actor.id()
                ),
                step.applied()
        );
    }


    /**
     * The trigger of the answer, once its shape is right: a rejection has a reason, a counter-offer an amount, and only
     * a counter-offer has one (400 otherwise).
     */
    private static AfterSalesTrigger trigger(AfterSalesResponse response) {
        AfterSalesResponse.Type type = response.type();
        if (type == AfterSalesResponse.Type.OFFER && response.offerAmount() == null) {
            throw new BadRequestException(AMOUNT_REQUIRED);
        }
        if (type != AfterSalesResponse.Type.OFFER && response.offerAmount() != null) {
            throw new BadRequestException(AMOUNT_NOT_EXPECTED);
        }
        if (type == AfterSalesResponse.Type.REJECT && note(response) == null) {
            throw new BadRequestException(REASON_REQUIRED);
        }
        return switch (type) {
            case APPROVE -> AfterSalesTrigger.MERCHANT_APPROVE;
            case REJECT -> AfterSalesTrigger.MERCHANT_REJECT;
            case OFFER -> AfterSalesTrigger.MERCHANT_OFFER;
        };
    }


    private static String note(AfterSalesResponse response) {
        return response.note() == null || response.note().isBlank() ? null : response.note().trim();
    }


    /**
     * The after-sales actor of the order module's actor. Only a merchant may answer, which the after-sales machine
     * enforces; a rider has no after-sales role at all.
     */
    private static AfterSalesActor actorType(OrderActor actor) {
        if (actor.type() == OrderActor.Type.RIDER) {
            throw new IllegalStateException("after-sales: a RIDER may not answer an after-sales request");
        }
        return AfterSalesActor.valueOf(actor.type().name());
    }


    /**
     * The counter-offer in the case's currency (ADR 0002 sections 5.2 and 19.5). An amount with more decimal places
     * than the currency has is refused with a message of its own, not Money's; one too large to be money at all is
     * not less than the requested amount. Whether it is more than zero and less than the requested amount is the
     * single writer's check.
     */
    private static Money offerAmount(BigDecimal amount, AfterSalesCaseEntity locked) {
        Currency currency = Money.currency(locked.currency());
        int digits = currency.getDefaultFractionDigits();
        if (amount.stripTrailingZeros().scale() > digits) {
            throw new BadRequestException("Amount must have at most " + digits + " decimal places");
        }
        if (amount.abs().compareTo(Money.MAX_AMOUNT) > 0) {
            throw new BadRequestException("The amount must be more than 0 and less than the requested "
                    + Money.of(locked.requestedAmount(), locked.currency()));
        }
        return new Money(amount, currency);
    }
}
