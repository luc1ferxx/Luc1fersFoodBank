package com.laioffer.onlineorder.audit;


import com.laioffer.onlineorder.service.CustomerService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;


import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


/**
 * Turns the request's principal into the {@link AuditActor} of an audited action: the login's customer id and its
 * most privileged role (ROLE_ADMIN before ROLE_MERCHANT, ROLE_RIDER and ROLE_USER).
 */
@Component
public class AuditActorResolver {

    private static final List<String> ROLES_BY_PRIVILEGE = List.of(
            AuditActor.ROLE_ADMIN,
            "ROLE_MERCHANT",
            "ROLE_RIDER",
            "ROLE_USER"
    );

    private final CustomerService customerService;


    public AuditActorResolver(CustomerService customerService) {
        this.customerService = customerService;
    }


    public AuditActor resolve(Principal principal) {
        if (principal == null) {
            return AuditActor.system();
        }
        Long customerId = customerService.getCustomerByEmail(principal.getName()).id();
        return new AuditActor(customerId, role(principal));
    }


    private static String role(Principal principal) {
        if (!(principal instanceof Authentication authentication)) {
            return AuditActor.ROLE_SYSTEM;
        }
        // Only the ROLE_* authorities: Spring Security 7 adds FACTOR_PASSWORD to every password login, which must not
        // become the fallback role below.
        Set<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .collect(Collectors.toSet());
        for (String role : ROLES_BY_PRIVILEGE) {
            if (authorities.contains(role)) {
                return role;
            }
        }
        return authorities.stream().sorted().findFirst().orElse(AuditActor.ROLE_SYSTEM);
    }
}
