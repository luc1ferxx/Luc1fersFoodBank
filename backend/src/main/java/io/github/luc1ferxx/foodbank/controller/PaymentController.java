package io.github.luc1ferxx.foodbank.controller;


import io.github.luc1ferxx.foodbank.entity.CustomerEntity;
import io.github.luc1ferxx.foodbank.model.OrderDto;
import io.github.luc1ferxx.foodbank.model.PaymentCheckoutBody;
import io.github.luc1ferxx.foodbank.service.CustomerService;
import io.github.luc1ferxx.foodbank.service.PaymentService;
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
