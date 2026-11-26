package io.github.luc1ferxx.foodbank.controller;


import io.github.luc1ferxx.foodbank.entity.CustomerEntity;
import io.github.luc1ferxx.foodbank.model.CurrentUserDto;
import io.github.luc1ferxx.foodbank.service.CustomerService;
import io.github.luc1ferxx.foodbank.staff.StaffProvisioningService;
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
        // Only the ROLE_* authorities: Spring Security 7 adds FACTOR_PASSWORD to every password login.
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
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
