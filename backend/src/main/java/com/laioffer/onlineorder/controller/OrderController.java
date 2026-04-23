package com.laioffer.onlineorder.controller;


import com.laioffer.onlineorder.entity.CustomerEntity;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.model.UpdateOrderStatusBody;
import com.laioffer.onlineorder.service.CustomerService;
import com.laioffer.onlineorder.service.OrderService;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;


import java.security.Principal;
import java.util.List;


@RestController
public class OrderController {

    private final CustomerService customerService;
    private final OrderService orderService;


    public OrderController(CustomerService customerService, OrderService orderService) {
        this.customerService = customerService;
        this.orderService = orderService;
    }


    @GetMapping("/orders")
    public List<OrderDto> getOrders(Principal principal) {
        CustomerEntity customer = customerService.getCustomerByEmail(principal.getName());
        return orderService.getOrdersByCustomerId(customer.id());
    }


    @PatchMapping("/orders/{orderId}/status")
    public OrderDto updateOrderStatus(
            @PathVariable Long orderId,
            @RequestBody UpdateOrderStatusBody body
    ) {
        return orderService.transitionOrderStatus(orderId, body.status());
    }


    @PostMapping("/orders/{orderId}/cancel")
    public OrderDto cancelOrder(Principal principal, @PathVariable Long orderId) {
        CustomerEntity customer = customerService.getCustomerByEmail(principal.getName());
        return orderService.cancelOrder(customer.id(), orderId);
    }
}
