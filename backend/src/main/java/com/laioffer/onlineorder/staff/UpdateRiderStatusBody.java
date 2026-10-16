package com.laioffer.onlineorder.staff;


import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;


/**
 * PATCH /admin/riders/{riderId}: {"active": boolean} and/or {"on_duty": boolean}; at least one is required. A field
 * that is left out keeps its value, except that deactivating also takes the rider off duty. An inactive rider cannot
 * be put on duty (409 RIDER_INACTIVE).
 */
public record UpdateRiderStatusBody(
        Boolean active,
        Boolean onDuty
) {

    @JsonIgnore
    @AssertTrue(message = "active or on_duty is required")
    public boolean isAnyFlagSet() {
        return active != null || onDuty != null;
    }
}
