package com.laioffer.onlineorder.fulfilment.merchant;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laioffer.onlineorder.entity.CustomerEntity;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.fulfilment.AfterCommit;
import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.model.OrderHistoryItemDto;
import com.laioffer.onlineorder.model.OrderStatus;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.service.CustomerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;


/**
 * The merchant's view of and decisions on the orders of their restaurant.
 *
 * <p>Every command is scoped to the merchant's restaurant twice: the inbox row is looked up (and locked) by order id
 * and restaurant id, and the order module checks the order's restaurant again under its row lock. An order of
 * another restaurant is reported as 404, the same as an order that does not exist. The order transition, its
 * outbox event and the inbox update commit in one transaction.
 */
@Service
public class MerchantOrderService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MerchantOrderService.class);
    private static final TypeReference<List<OrderHistoryItemDto>> ITEMS_TYPE = new TypeReference<>() {
    };

    private final CustomerService customerService;
    private final MerchantAccountRepository merchantAccountRepository;
    private final MerchantOrderRepository merchantOrderRepository;
    private final OrderLifecycleCommands orderLifecycleCommands;
    private final ApplicationMetricsService metricsService;
    private final ObjectMapper objectMapper;


    public MerchantOrderService(
            CustomerService customerService,
            MerchantAccountRepository merchantAccountRepository,
            MerchantOrderRepository merchantOrderRepository,
            OrderLifecycleCommands orderLifecycleCommands,
            ApplicationMetricsService metricsService,
            ObjectMapper objectMapper
    ) {
        this.customerService = customerService;
        this.merchantAccountRepository = merchantAccountRepository;
        this.merchantOrderRepository = merchantOrderRepository;
        this.orderLifecycleCommands = orderLifecycleCommands;
        this.metricsService = metricsService;
        this.objectMapper = objectMapper;
    }


    public List<MerchantOrderDto> getOrders(String merchantEmail, String status, int limit) {
        Long restaurantId = requireRestaurantId(merchantEmail);
        int normalizedLimit = Math.max(1, Math.min(limit, 100));
        List<MerchantOrderEntity> orders = status == null || status.isBlank()
                ? merchantOrderRepository.findPageByRestaurantId(restaurantId, normalizedLimit)
                : merchantOrderRepository.findPageByRestaurantIdAndStatus(
                        restaurantId,
                        OrderStatus.normalize(status).name(),
                        normalizedLimit
                );
        return orders.stream().map(this::toDto).toList();
    }


    @Transactional
    public MerchantOrderDto accept(String merchantEmail, Long orderId) {
        return decide(merchantEmail, orderId, OrderStatus.PAID, OrderStatus.ACCEPTED, null, "accepted");
    }


    @Transactional
    public MerchantOrderDto reject(String merchantEmail, Long orderId, String reason) {
        String cancelReason = OrderLifecycleCommands.MERCHANT_REJECTED + ": " + reason.trim();
        return decide(merchantEmail, orderId, OrderStatus.PAID, OrderStatus.CANCELLED, cancelReason, "rejected");
    }


    @Transactional
    public MerchantOrderDto markPreparing(String merchantEmail, Long orderId) {
        return decide(merchantEmail, orderId, OrderStatus.ACCEPTED, OrderStatus.PREPARING, null, null);
    }


    private MerchantOrderDto decide(
            String merchantEmail,
            Long orderId,
            OrderStatus requiredStatus,
            OrderStatus targetStatus,
            String cancelReason,
            String decision
    ) {
        Long restaurantId = requireRestaurantId(merchantEmail);
        MerchantOrderEntity inboxOrder = merchantOrderRepository.lockByOrderIdAndRestaurantId(orderId, restaurantId);
        if (inboxOrder == null) {
            throw new ResourceNotFoundException("Order not found");
        }

        OrderDto order = orderLifecycleCommands.transitionRestaurantOrder(
                orderId,
                restaurantId,
                requiredStatus,
                targetStatus,
                cancelReason
        );
        LocalDateTime now = LocalDateTime.now();
        merchantOrderRepository.updateStatus(inboxOrder.id(), order.status(), order.cancelReason(), now);

        // Count only a decision this command made: not a retry, and not a reject answered with an order the accept
        // timeout had already cancelled.
        boolean transitioned = requiredStatus.name().equals(inboxOrder.status())
                && targetStatus.name().equals(order.status())
                && (targetStatus != OrderStatus.CANCELLED || Objects.equals(cancelReason, order.cancelReason()));
        if (transitioned && decision != null) {
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
                inboxOrder.restaurantId(),
                inboxOrder.customerId(),
                order.status(),
                inboxOrder.totalPrice(),
                inboxOrder.items(),
                order.cancelReason(),
                inboxOrder.paidAt(),
                inboxOrder.createdAt(),
                now
        ));
    }


    /**
     * @throws AccessDeniedException (403) when the login is not bound to a restaurant
     */
    private Long requireRestaurantId(String merchantEmail) {
        CustomerEntity merchant = customerService.getCustomerByEmail(merchantEmail);
        MerchantAccountEntity account = merchantAccountRepository.findByCustomerId(merchant.id());
        if (account == null) {
            throw new AccessDeniedException("Merchant account is not bound to a restaurant");
        }
        return account.restaurantId();
    }


    private MerchantOrderDto toDto(MerchantOrderEntity entity) {
        return new MerchantOrderDto(
                entity.orderId(),
                entity.restaurantId(),
                entity.customerId(),
                entity.status(),
                entity.totalPrice(),
                readItems(entity.items()),
                entity.cancelReason(),
                entity.paidAt(),
                entity.updatedAt()
        );
    }


    private List<MerchantOrderDto.Item> readItems(String items) {
        try {
            return objectMapper.readValue(items, ITEMS_TYPE).stream()
                    .map(item -> new MerchantOrderDto.Item(
                            item.menuItemId(),
                            item.menuItemName(),
                            item.price(),
                            item.quantity()
                    ))
                    .toList();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to read merchant order items", ex);
        }
    }
}
