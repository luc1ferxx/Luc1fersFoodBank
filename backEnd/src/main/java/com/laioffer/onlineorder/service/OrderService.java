package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.entity.OrderHistoryItemEntity;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.model.OrderHistoryItemDto;
import com.laioffer.onlineorder.repository.OrderHistoryItemRepository;
import com.laioffer.onlineorder.repository.OrderRepository;
import org.springframework.stereotype.Service;


import java.util.ArrayList;
import java.util.List;


@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderHistoryItemRepository orderHistoryItemRepository;


    public OrderService(
            OrderRepository orderRepository,
            OrderHistoryItemRepository orderHistoryItemRepository
    ) {
        this.orderRepository = orderRepository;
        this.orderHistoryItemRepository = orderHistoryItemRepository;
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
        List<OrderHistoryItemEntity> orderItems = orderHistoryItemRepository.findAllByOrderIdOrderByIdAsc(order.id());
        List<OrderHistoryItemDto> itemDtos = new ArrayList<>();
        for (OrderHistoryItemEntity orderItem : orderItems) {
            itemDtos.add(new OrderHistoryItemDto(orderItem));
        }
        return new OrderDto(order, itemDtos);
    }
}
