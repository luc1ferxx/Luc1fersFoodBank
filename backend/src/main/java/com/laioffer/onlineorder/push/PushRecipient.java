package com.laioffer.onlineorder.push;


import java.util.Locale;
import java.util.Objects;


/**
 * Whom a push signal is for: a customer login ({@code customer:{customerId}}), the merchant logins of a restaurant
 * ({@code restaurant:{restaurantId}}) or a rider ({@code rider:{riderId}}). Writers name the recipient from the row
 * they changed; a stream subscribes to the recipients of its login.
 *
 * @param kind the recipient form
 * @param id   the customer, restaurant or rider id
 */
public record PushRecipient(Kind kind, long id) {

    /**
     * The recipient forms, each with the prefix of its wire name.
     */
    public enum Kind {
        CUSTOMER,
        RESTAURANT,
        RIDER;


        String prefix() {
            return name().toLowerCase(Locale.ROOT);
        }
    }


    public PushRecipient {
        Objects.requireNonNull(kind, "kind");
    }


    public static PushRecipient customer(long customerId) {
        return new PushRecipient(Kind.CUSTOMER, customerId);
    }


    public static PushRecipient restaurant(long restaurantId) {
        return new PushRecipient(Kind.RESTAURANT, restaurantId);
    }


    public static PushRecipient rider(long riderId) {
        return new PushRecipient(Kind.RIDER, riderId);
    }


    /**
     * Reads a wire name such as {@code restaurant:5}.
     *
     * @throws IllegalArgumentException for anything else
     */
    public static PushRecipient parse(String wireName) {
        int separator = wireName == null ? -1 : wireName.indexOf(':');
        if (separator > 0) {
            String prefix = wireName.substring(0, separator);
            for (Kind kind : Kind.values()) {
                if (kind.prefix().equals(prefix)) {
                    try {
                        return new PushRecipient(kind, Long.parseLong(wireName.substring(separator + 1)));
                    } catch (NumberFormatException ex) {
                        break;
                    }
                }
            }
        }
        throw new IllegalArgumentException("Unknown push recipient: " + wireName);
    }


    /**
     * The wire name, {@code customer:42} and so on.
     */
    public String wireName() {
        return kind.prefix() + ":" + id;
    }


    @Override
    public String toString() {
        return wireName();
    }
}
