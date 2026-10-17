package com.laioffer.onlineorder.model;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;


/**
 * POST /admin/outbox/events/{eventId}/abandon.
 *
 * @param reason why the event can never be published and how its effect is reconciled; kept in the audit log
 */
public record AbandonOutboxEventBody(
        @NotBlank
        @Size(max = 500)
        String reason
) {
}
