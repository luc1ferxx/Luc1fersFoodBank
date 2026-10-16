package com.laioffer.onlineorder.staff;


import com.laioffer.onlineorder.entity.CustomerEntity;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


class StaffProvisioningServiceTests {

    @Test
    void displayName_shouldJoinTheGivenNamesOrFallBackToTheEmail() {
        Assertions.assertEquals("Dana Rider", StaffProvisioningService.displayName(login(" Dana ", "Rider")));
        Assertions.assertEquals("Dana", StaffProvisioningService.displayName(login("Dana", null)));
        Assertions.assertEquals("Rider", StaffProvisioningService.displayName(login(" ", "Rider")));
        Assertions.assertEquals("dana@example.com", StaffProvisioningService.displayName(login(null, " ")));
    }


    private static CustomerEntity login(String firstName, String lastName) {
        return new CustomerEntity(
                9L, "dana@example.com", "{bcrypt}x", true, firstName, lastName, "ACTIVE", true, 0,
                null, null, null, null);
    }
}
