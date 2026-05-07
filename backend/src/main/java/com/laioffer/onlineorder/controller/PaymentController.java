package com.laioffer.onlineorder.controller;


import com.laioffer.onlineorder.entity.CustomerEntity;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.model.PaymentCheckoutBody;
import com.laioffer.onlineorder.service.CustomerService;
import com.laioffer.onlineorder.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;


import java.security.Principal;


@RestController
public class PaymentController {

    private final CustomerService customerService;
    private final PaymentService paymentService;


    public PaymentController(CustomerService customerService, PaymentService paymentService) {
        this.customerService = customerService;
        this.paymentService = paymentService;
    }


    @PostMapping("/payments/checkout")
    public OrderDto payAndCheckout(
            Principal principal,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody(required = false) PaymentCheckoutBody body
    ) {
        CustomerEntity customer = customerService.getCustomerByEmail(principal.getName());
        return paymentService.payAndCheckout(customer.id(), idempotencyKey, body);
    }
}
