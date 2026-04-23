package com.laioffer.onlineorder.model;


public record PaymentCheckoutBody(
        String cardholderName,
        String cardNumber,
        String expiry,
        String cvv
) {
}
