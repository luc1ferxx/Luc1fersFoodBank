package io.github.luc1ferxx.foodbank.model;


public record PaymentCheckoutBody(
        String cardholderName,
        String cardNumber,
        String expiry,
        String cvv
) {
}
