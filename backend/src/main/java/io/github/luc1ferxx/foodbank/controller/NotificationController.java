package io.github.luc1ferxx.foodbank.controller;


import io.github.luc1ferxx.foodbank.entity.CustomerEntity;
import io.github.luc1ferxx.foodbank.model.OrderNotificationDto;
import io.github.luc1ferxx.foodbank.service.CustomerService;
import io.github.luc1ferxx.foodbank.service.OrderNotificationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


import java.security.Principal;
import java.util.List;


@RestController
public class NotificationController {

    private final CustomerService customerService;
    private final OrderNotificationService orderNotificationService;


    public NotificationController(
            CustomerService customerService,
            OrderNotificationService orderNotificationService
    ) {
        this.customerService = customerService;
        this.orderNotificationService = orderNotificationService;
    }


    @GetMapping("/notifications")
    public List<OrderNotificationDto> getNotifications(
            Principal principal,
            @RequestParam(defaultValue = "50") int limit
    ) {
        CustomerEntity customer = customerService.getCustomerByEmail(principal.getName());
        return orderNotificationService.getNotificationsByCustomerId(customer.id(), limit);
    }
}
