package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.entity.OrderHistoryItemEntity;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.model.OrderHistoryItemDto;
import com.laioffer.onlineorder.model.OrderStatus;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.repository.OrderHistoryItemRepository;
import com.laioffer.onlineorder.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.util.ArrayList;
import java.util.List;


@Service
public class OrderService {

    private final ApplicationMetricsService metricsService;
    private final OrderEventOutboxService orderEventOutboxService;
    private final OrderRepository orderRepository;
    private final OrderHistoryItemRepository orderHistoryItemRepository;


    public OrderService(
            OrderRepository orderRepository,
            OrderHistoryItemRepository orderHistoryItemRepository,
            OrderEventOutboxService orderEventOutboxService,
            ApplicationMetricsService metricsService
    ) {
        this.orderRepository = orderRepository;
        this.orderHistoryItemRepository = orderHistoryItemRepository;
        this.orderEventOutboxService = orderEventOutboxService;
        this.metricsService = metricsService;
    }


    public List<OrderDto> getOrdersByCustomerId(Long customerId) {
        List<OrderEntity> orders = orderRepository.findAllByCustomerIdOrderByCreatedAtDesc(customerId);
        List<OrderDto> results = new ArrayList<>();
        for (OrderEntity order : orders) {
            results.add(getOrderDto(order));
        }
        return results;
    }


    public OrderDto getOrderDto(OrderEntity order) {
        return new OrderDto(order, getOrderItems(order.id()));
    }


    @Transactional
    public OrderDto transitionOrderStatus(Long orderId, String targetStatus) {
        OrderEntity order = orderRepository.lockById(orderId);
        if (order == null) {
            throw new ResourceNotFoundException("Order not found");
        }
        return transitionLockedOrder(order, OrderStatus.normalize(targetStatus));
    }


    @Transactional
    public OrderDto cancelOrder(Long customerId, Long orderId) {
        OrderEntity order = orderRepository.lockByIdAndCustomerId(orderId, customerId);
        if (order == null) {
            throw new ResourceNotFoundException("Order not found");
        }
        return transitionLockedOrder(order, OrderStatus.CANCELLED);
    }


    private OrderDto transitionLockedOrder(OrderEntity currentOrder, OrderStatus targetStatus) {
        OrderStatus currentStatus = OrderStatus.normalize(currentOrder.status());
        currentStatus.validateTransitionTo(targetStatus);
        if (currentStatus == targetStatus) {
            return getOrderDto(currentOrder);
        }

        OrderEntity updatedOrder = orderRepository.save(new OrderEntity(
                currentOrder.id(),
                currentOrder.customerId(),
                currentOrder.totalPrice(),
                targetStatus.name(),
                currentOrder.createdAt()
        ));
        List<OrderHistoryItemDto> items = getOrderItems(updatedOrder.id());
        orderEventOutboxService.enqueueOrderEvent(updatedOrder, items, null);
        metricsService.recordOrderTransition(currentStatus.name(), targetStatus.name());
        return new OrderDto(updatedOrder, items);
    }


    private List<OrderHistoryItemDto> getOrderItems(Long orderId) {
        List<OrderHistoryItemEntity> orderItems = orderHistoryItemRepository.findAllByOrderIdOrderByIdAsc(orderId);
        List<OrderHistoryItemDto> itemDtos = new ArrayList<>();
        for (OrderHistoryItemEntity orderItem : orderItems) {
            itemDtos.add(new OrderHistoryItemDto(orderItem));
        }
        return itemDtos;
    }
}
