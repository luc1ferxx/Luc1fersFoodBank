package com.laioffer.onlineorder;

import com.laioffer.onlineorder.controller.CartController;
import com.laioffer.onlineorder.controller.PaymentController;
import com.laioffer.onlineorder.entity.CustomerEntity;
import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.model.PaymentCheckoutBody;
import com.laioffer.onlineorder.service.CartService;
import com.laioffer.onlineorder.service.CustomerService;
import com.laioffer.onlineorder.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.Principal;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CheckoutControllerBoundaryTests {

    @Mock
    private CartService cartService;

    @Mock
    private CustomerService customerService;

    @Mock
    private PaymentService paymentService;

    private MockMvc cartMockMvc;
    private MockMvc paymentMockMvc;


    @BeforeEach
    void setup() {
        cartMockMvc = MockMvcBuilders.standaloneSetup(new CartController(cartService, customerService)).build();
        paymentMockMvc = MockMvcBuilders.standaloneSetup(new PaymentController(customerService, paymentService)).build();
    }


    @Test
    void cartCheckout_whenIdempotencyHeaderIsMissing_shouldDelegateToServiceValidation() throws Exception {
        CustomerEntity customer = customer();
        Mockito.when(customerService.getCustomerByEmail("buyer@example.com")).thenReturn(customer);
        Mockito.when(cartService.checkoutWithIdempotency(customer.id(), "PLACED", null))
                .thenThrow(new BadRequestException("Idempotency-Key header is required"));

        cartMockMvc.perform(post("/cart/checkout").principal(principal()))
                .andExpect(status().isBadRequest());

        Mockito.verify(cartService).checkoutWithIdempotency(customer.id(), "PLACED", null);
    }


    @Test
    void paymentCheckout_whenBodyIsMissing_shouldDelegateToServiceValidation() throws Exception {
        CustomerEntity customer = customer();
        Mockito.when(customerService.getCustomerByEmail("buyer@example.com")).thenReturn(customer);
        Mockito.when(paymentService.payAndCheckout(customer.id(), "key-1", null))
                .thenThrow(new BadRequestException("Payment details are required"));

        paymentMockMvc.perform(post("/payments/checkout")
                        .principal(principal())
                        .header("Idempotency-Key", "key-1"))
                .andExpect(status().isBadRequest());

        Mockito.verify(paymentService).payAndCheckout(customer.id(), "key-1", null);
    }


    @Test
    void paymentCheckout_whenIdempotencyHeaderIsMissing_shouldDelegateToServiceValidation() throws Exception {
        CustomerEntity customer = customer();
        Mockito.when(customerService.getCustomerByEmail("buyer@example.com")).thenReturn(customer);
        Mockito.when(paymentService.payAndCheckout(Mockito.eq(customer.id()), Mockito.isNull(), Mockito.any(PaymentCheckoutBody.class)))
                .thenThrow(new BadRequestException("Idempotency-Key header is required"));

        paymentMockMvc.perform(post("/payments/checkout")
                        .principal(principal())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cardholderName": "Buyer",
                                  "cardNumber": "4242424242424242",
                                  "expiry": "12/30",
                                  "cvv": "123"
                                }
                                """))
                .andExpect(status().isBadRequest());

        Mockito.verify(paymentService).payAndCheckout(Mockito.eq(customer.id()), Mockito.isNull(), Mockito.any(PaymentCheckoutBody.class));
    }


    @Test
    void paymentCheckout_whenPaymentFieldsAreInvalid_shouldDelegateToServiceValidation() throws Exception {
        CustomerEntity customer = customer();
        Mockito.when(customerService.getCustomerByEmail("buyer@example.com")).thenReturn(customer);
        Mockito.when(paymentService.payAndCheckout(Mockito.eq(customer.id()), Mockito.eq("key-1"), Mockito.any(PaymentCheckoutBody.class)))
                .thenThrow(new BadRequestException("Cardholder name is required"));

        paymentMockMvc.perform(post("/payments/checkout")
                        .principal(principal())
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cardholderName": "",
                                  "cardNumber": "4242424242424242",
                                  "expiry": "12/30",
                                  "cvv": "123"
                                }
                                """))
                .andExpect(status().isBadRequest());

        Mockito.verify(paymentService).payAndCheckout(Mockito.eq(customer.id()), Mockito.eq("key-1"), Mockito.any(PaymentCheckoutBody.class));
    }


    private Principal principal() {
        return () -> "buyer@example.com";
    }


    private CustomerEntity customer() {
        LocalDateTime now = LocalDateTime.of(2026, 4, 4, 12, 0);
        return new CustomerEntity(
                1L,
                "buyer@example.com",
                "{noop}demo123",
                true,
                "Buyer",
                "User",
                "ACTIVE",
                true,
                0,
                null,
                null,
                now,
                now
        );
    }
}
