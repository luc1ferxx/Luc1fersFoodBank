package com.laioffer.onlineorder.fulfilment.merchant;


import com.laioffer.onlineorder.datasource.ReadOnlyTransactions;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.fulfilment.AfterCommit;
import com.laioffer.onlineorder.model.OrderActor;
import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands;
import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands.RestaurantOrderTransition;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.model.OrderHistoryItemDto;
import com.laioffer.onlineorder.model.OrderStatus;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.push.PushRecipient;
import com.laioffer.onlineorder.push.PushSignals;
import com.laioffer.onlineorder.push.PushTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;


import java.time.LocalDateTime;
import java.util.List;


/**
 * The merchant's view of and decisions on the orders of their restaurant.
 *
 * <p>Every command is scoped to the merchant's restaurant twice: the inbox row is looked up (and locked) by order id
 * and restaurant id, and the order module checks the order's restaurant again under its row lock. An order of
 * another restaurant is reported as 404, the same as an order that does not exist. The order transition, its
 * outbox event and the inbox update commit in one transaction.
 *
 * <p>The caller passes the merchant's customer id, resolved from the login by the web layer like every other
 * controller does; the module only reads its own tables.
 */
@Service
public class MerchantOrderService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MerchantOrderService.class);
    private static final TypeReference<List<OrderHistoryItemDto>> ITEMS_TYPE = new TypeReference<>() {
    };

    private final MerchantAccountRepository merchantAccountRepository;
    private final MerchantOrderRepository merchantOrderRepository;
    private final OrderLifecycleCommands orderLifecycleCommands;
    private final ApplicationMetricsService metricsService;
    private final ObjectMapper objectMapper;
    private final ReadOnlyTransactions readOnlyTransactions;
    private final PushSignals pushSignals;


    public MerchantOrderService(
            MerchantAccountRepository merchantAccountRepository,
            MerchantOrderRepository merchantOrderRepository,
            OrderLifecycleCommands orderLifecycleCommands,
            ApplicationMetricsService metricsService,
            ObjectMapper objectMapper,
            ReadOnlyTransactions readOnlyTransactions,
            PushSignals pushSignals
    ) {
        this.merchantAccountRepository = merchantAccountRepository;
        this.merchantOrderRepository = merchantOrderRepository;
        this.orderLifecycleCommands = orderLifecycleCommands;
        this.metricsService = metricsService;
        this.objectMapper = objectMapper;
        this.readOnlyTransactions = readOnlyTransactions;
        this.pushSignals = pushSignals;
    }


    /**
     * The merchant's inbox page. The account check runs first, outside any transaction, so it reads the primary and a
     * deactivation takes effect on the next request whatever the replica lag; the page itself is a read-only
     * transaction (a replica read when replicas are configured).
     */
    public List<MerchantOrderDto> getOrders(Long merchantCustomerId, String status, int limit) {
        Long restaurantId = requireRestaurantId(merchantCustomerId);
        int normalizedLimit = Math.max(1, Math.min(limit, 100));
        String normalizedStatus = status == null || status.isBlank() ? null : OrderStatus.normalize(status).name();
        List<MerchantOrderEntity> orders = readOnlyTransactions.read("MerchantOrderService.getOrders", () ->
                normalizedStatus == null
                        ? merchantOrderRepository.findPageByRestaurantId(restaurantId, normalizedLimit)
                        : merchantOrderRepository.findPageByRestaurantIdAndStatus(
                                restaurantId,
                                normalizedStatus,
                                normalizedLimit
                        ));
        return orders.stream().map(this::toDto).toList();
    }


    @Transactional
    public MerchantOrderDto accept(Long merchantCustomerId, Long orderId) {
        return decide(merchantCustomerId, orderId, OrderStatus.PAID, OrderStatus.ACCEPTED, null, "accepted");
    }


    @Transactional
    public MerchantOrderDto reject(Long merchantCustomerId, Long orderId, String reason) {
        String cancelReason = OrderLifecycleCommands.MERCHANT_REJECTED + ": " + reason.trim();
        return decide(merchantCustomerId, orderId, OrderStatus.PAID, OrderStatus.CANCELLED, cancelReason, "rejected");
    }


    @Transactional
    public MerchantOrderDto markPreparing(Long merchantCustomerId, Long orderId) {
        return decide(merchantCustomerId, orderId, OrderStatus.ACCEPTED, OrderStatus.PREPARING, null, null);
    }


    private MerchantOrderDto decide(
            Long merchantCustomerId,
            Long orderId,
            OrderStatus requiredStatus,
            OrderStatus targetStatus,
            String cancelReason,
            String decision
    ) {
        Long restaurantId = requireRestaurantId(merchantCustomerId);
        MerchantOrderEntity inboxOrder = merchantOrderRepository.lockByOrderIdAndRestaurantId(orderId, restaurantId);
        if (inboxOrder == null) {
            throw new ResourceNotFoundException("Order not found");
        }

        RestaurantOrderTransition transition = orderLifecycleCommands.transitionRestaurantOrder(
                orderId,
                restaurantId,
                requiredStatus,
                targetStatus,
                cancelReason,
                OrderActor.merchant(merchantCustomerId)
        );
        OrderDto order = transition.order();
        LocalDateTime now = LocalDateTime.now();
        merchantOrderRepository.updateStatus(inboxOrder.id(), order.status(), order.cancelReason(), now);
        if (!order.status().equals(inboxOrder.status())) {
            // The restaurant's other merchant tabs and logins re-read the inbox (push topic merchant.orders).
            pushSignals.publish(PushRecipient.restaurant(restaurantId), PushTopic.MERCHANT_ORDERS);
        }

        // Count only a decision this command made: not a retry, and not an order an admin had already moved before
        // this inbox row caught up (the order module reports which).
        if (transition.changed() && decision != null) {
            AfterCommit.run(() -> metricsService.recordMerchantDecision(decision));
        }
        LOGGER.info(
                "Merchant order command: restaurant_id={}, order_id={}, from={}, to={}, status={}",
                restaurantId,
                orderId,
                inboxOrder.status(),
                targetStatus,
                order.status()
        );
        return toDto(new MerchantOrderEntity(
                inboxOrder.id(),
                inboxOrder.orderId(),
                inboxOrder.orderNo(),
                inboxOrder.restaurantId(),
                inboxOrder.customerId(),
                order.status(),
                inboxOrder.totalPrice(),
                inboxOrder.items(),
                order.cancelReason(),
                inboxOrder.paidAt(),
                inboxOrder.createdAt(),
                now,
                inboxOrder.currency()
        ));
    }


    /**
     * Looked up on every request, so a deactivation takes effect on the merchant's next request even though the
     * session still carries ROLE_MERCHANT.
     *
     * @throws AccessDeniedException (403) when the login is not bound to a restaurant or the account is deactivated
     */
    private Long requireRestaurantId(Long merchantCustomerId) {
        return MerchantAccounts.requireActiveRestaurantId(merchantAccountRepository, merchantCustomerId);
    }


    private MerchantOrderDto toDto(MerchantOrderEntity entity) {
        return new MerchantOrderDto(
                entity.orderId(),
                entity.orderNo(),
                entity.restaurantId(),
                entity.customerId(),
                entity.status(),
                entity.totalPrice(),
                entity.currency(),
                readItems(entity.items(), entity.currency()),
                entity.cancelReason(),
                entity.paidAt(),
                entity.updatedAt()
        );
    }


    /**
     * The order lines stored with the inbox row, priced in the row's currency (an order is single-currency; the lines
     * of an order event carry no currency of their own).
     */
    private List<MerchantOrderDto.Item> readItems(String items, String currency) {
        try {
            return objectMapper.readValue(items, ITEMS_TYPE).stream()
                    .map(item -> new MerchantOrderDto.Item(
                            item.menuItemId(),
                            item.menuItemName(),
                            item.price(),
                            currency,
                            item.quantity()
                    ))
                    .toList();
        } catch (JacksonException ex) {
            throw new IllegalStateException("Unable to read merchant order items", ex);
        }
    }
}
