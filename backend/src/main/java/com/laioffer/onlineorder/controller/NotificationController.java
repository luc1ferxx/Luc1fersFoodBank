package com.laioffer.onlineorder.controller;


import com.laioffer.onlineorder.entity.CustomerEntity;
import com.laioffer.onlineorder.model.OrderNotificationDto;
import com.laioffer.onlineorder.service.CustomerService;
import com.laioffer.onlineorder.service.OrderNotificationService;
import org.springframework.web.bind.annotation.GetMapping;
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
    public List<OrderNotificationDto> getNotifications(Principal principal) {
        CustomerEntity customer = customerService.getCustomerByEmail(principal.getName());
        return orderNotificationService.getNotificationsByCustomerId(customer.id());
    }
}
