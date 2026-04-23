package com.laioffer.onlineorder;


import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.model.PaymentCheckoutBody;
import com.laioffer.onlineorder.service.CartService;
import com.laioffer.onlineorder.service.PaymentService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;


import java.time.LocalDateTime;
import java.util.List;


@ExtendWith(MockitoExtension.class)
public class PaymentServiceTests {

    @Mock
    private CartService cartService;

    private PaymentService paymentService;


    @BeforeEach
    void setup() {
        paymentService = new PaymentService(cartService);
    }


    @Test
    void payAndCheckout_shouldValidatePaymentAndCreatePaidOrder() {
        PaymentCheckoutBody body = new PaymentCheckoutBody(
                "Luc1fer Buyer",
                "4242 4242 4242 4242",
                "12/30",
                "123"
        );
        OrderDto paidOrder = new OrderDto(
                5L,
                42.0,
                "PAID",
                LocalDateTime.of(2026, 4, 4, 12, 0),
                List.of()
        );
        Mockito.when(cartService.checkoutWithIdempotency(Mockito.eq(1L), Mockito.eq("PAID"), Mockito.eq("key-1"), Mockito.anyString()))
                .thenReturn(paidOrder);

        OrderDto result = paymentService.payAndCheckout(1L, "key-1", body);

        Assertions.assertEquals("PAID", result.status());
        Mockito.verify(cartService).checkoutWithIdempotency(Mockito.eq(1L), Mockito.eq("PAID"), Mockito.eq("key-1"), Mockito.anyString());
    }


    @Test
    void payAndCheckout_shouldRejectExpiredCards() {
        PaymentCheckoutBody body = new PaymentCheckoutBody(
                "Luc1fer Buyer",
                "4242424242424242",
                "01/20",
                "123"
        );

        Assertions.assertThrows(BadRequestException.class, () -> paymentService.payAndCheckout(1L, "key-1", body));
        Mockito.verifyNoInteractions(cartService);
    }
}
