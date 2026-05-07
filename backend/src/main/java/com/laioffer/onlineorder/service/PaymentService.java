package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.model.PaymentCheckoutBody;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import org.springframework.stereotype.Service;


import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.YearMonth;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Service
public class PaymentService {

    private static final Pattern CARD_NUMBER_PATTERN = Pattern.compile("^\\d{16}$");
    private static final Pattern CVV_PATTERN = Pattern.compile("^\\d{3,4}$");
    private static final Pattern EXPIRY_PATTERN = Pattern.compile("^(0[1-9]|1[0-2])/(\\d{2})$");

    private final CartService cartService;
    private final ApplicationMetricsService metricsService;


    public PaymentService(CartService cartService, ApplicationMetricsService metricsService) {
        this.cartService = cartService;
        this.metricsService = metricsService;
    }


    public OrderDto payAndCheckout(Long customerId, String idempotencyKey, PaymentCheckoutBody body) {
        try {
            validate(body);
        } catch (RuntimeException ex) {
            metricsService.recordCheckoutFailure(ex.getClass().getSimpleName());
            throw ex;
        }
        return cartService.checkoutWithIdempotency(customerId, "PAID", idempotencyKey, buildRequestHash(customerId, body));
    }


    private void validate(PaymentCheckoutBody body) {
        if (body == null) {
            throw new BadRequestException("Payment details are required");
        }

        if (body.cardholderName() == null || body.cardholderName().trim().isEmpty()) {
            throw new BadRequestException("Cardholder name is required");
        }

        String cardNumber = normalizeDigits(body.cardNumber());
        if (!CARD_NUMBER_PATTERN.matcher(cardNumber).matches()) {
            throw new BadRequestException("Card number must contain 16 digits");
        }

        String cvv = normalizeDigits(body.cvv());
        if (!CVV_PATTERN.matcher(cvv).matches()) {
            throw new BadRequestException("Security code must contain 3 or 4 digits");
        }

        Matcher matcher = EXPIRY_PATTERN.matcher(body.expiry() == null ? "" : body.expiry().trim());
        if (!matcher.matches()) {
            throw new BadRequestException("Expiry must use MM/YY format");
        }

        int month = Integer.parseInt(matcher.group(1));
        int year = 2000 + Integer.parseInt(matcher.group(2));
        YearMonth expiry = YearMonth.of(year, month);
        if (expiry.isBefore(YearMonth.now())) {
            throw new BadRequestException("Card has expired");
        }
    }


    private String normalizeDigits(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }


    private String buildRequestHash(Long customerId, PaymentCheckoutBody body) {
        String normalizedPayload = String.join("|",
                String.valueOf(customerId),
                body.cardholderName().trim(),
                normalizeDigits(body.cardNumber()),
                body.expiry().trim()
        );

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalizedPayload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
