package com.laioffer.onlineorder.staff;


import jakarta.validation.constraints.NotNull;


/**
 * PATCH /admin/merchants/{merchantId} and PATCH /admin/riders/{riderId}.
 */
public record UpdateStaffStatusBody(
        @NotNull
        Boolean active
) {
}
