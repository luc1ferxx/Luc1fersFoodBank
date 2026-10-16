package com.laioffer.onlineorder.controller;


import com.laioffer.onlineorder.entity.CustomerEntity;
import com.laioffer.onlineorder.model.CurrentUserDto;
import com.laioffer.onlineorder.service.CustomerService;
import com.laioffer.onlineorder.staff.StaffProvisioningService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;


import java.util.List;


@RestController
public class SessionController {

    private final CustomerService customerService;
    private final StaffProvisioningService staffProvisioningService;


    public SessionController(CustomerService customerService, StaffProvisioningService staffProvisioningService) {
        this.customerService = customerService;
        this.staffProvisioningService = staffProvisioningService;
    }


    /**
     * The signed-in user with the roles of the session and, for staff, the merchant account or rider bound to the
     * login (read on every call, so a deactivation shows up at once as active=false).
     */
    @GetMapping("/me")
    public CurrentUserDto getCurrentUser(Authentication authentication) {
        CustomerEntity customer = customerService.getCustomerByEmail(authentication.getName());
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .distinct()
                .sorted()
                .toList();
        return new CurrentUserDto(
                customer,
                roles,
                staffProvisioningService.merchantOf(customer.id()).orElse(null),
                staffProvisioningService.riderOf(customer.id()).orElse(null)
        );
    }
}
