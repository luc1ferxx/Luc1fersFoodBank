package com.laioffer.onlineorder.staff;


import jakarta.validation.constraints.NotNull;


/**
 * PATCH /admin/merchants/{merchantId}. Riders take {@link UpdateRiderStatusBody}.
 */
public record UpdateStaffStatusBody(
        @NotNull
        Boolean active
) {
}
