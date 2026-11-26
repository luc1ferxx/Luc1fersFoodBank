package io.github.luc1ferxx.foodbank;


import io.github.luc1ferxx.foodbank.exception.BadRequestException;
import io.github.luc1ferxx.foodbank.model.OrderDto;
import io.github.luc1ferxx.foodbank.model.PaymentCheckoutBody;
import io.github.luc1ferxx.foodbank.money.Money;
import io.github.luc1ferxx.foodbank.observability.ApplicationMetricsService;
import io.github.luc1ferxx.foodbank.service.CartService;
import io.github.luc1ferxx.foodbank.service.PaymentService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;


@ExtendWith(MockitoExtension.class)
public class PaymentServiceTests {

    @Mock
    private CartService cartService;

    @Mock
    private ApplicationMetricsService metricsService;

    private PaymentService paymentService;


    @BeforeEach
    void setup() {
        paymentService = new PaymentService(cartService, metricsService);
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
                Money.of("42.0", "USD"),
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
        Mockito.verify(metricsService).recordCheckoutFailure("BadRequestException");
    }


    private BigDecimal amount(String value) {
        return new BigDecimal(value);
    }
}
