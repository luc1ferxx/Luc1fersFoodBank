package io.github.luc1ferxx.foodbank.staff;


import jakarta.validation.constraints.NotNull;


/**
 * PATCH /admin/merchants/{merchantId}. Riders take {@link UpdateRiderStatusBody}.
 */
public record UpdateStaffStatusBody(
        @NotNull
        Boolean active
) {
}
