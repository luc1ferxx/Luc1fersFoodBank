package com.laioffer.onlineorder.staff;


import com.laioffer.onlineorder.audit.AuditActorResolver;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;


import java.security.Principal;
import java.util.List;


/**
 * Admin provisioning of merchant and rider logins (ROLE_ADMIN only, see AppConfig; the POSTs and PATCHes are
 * rate-limited per admin like other admin writes). An email that any login already uses is 409
 * EMAIL_ALREADY_REGISTERED; an unknown restaurant, merchant or rider is 404. PATCH /admin/riders/{riderId} also takes
 * an optional on_duty, the operator's lever when no rider is on duty (an extension of the shared contract, whose
 * {"active": boolean} body keeps working). Every change is recorded in the audit log with the admin who made it.
 */
@RestController
public class StaffAdminController {

    private final StaffProvisioningService staffProvisioningService;
    private final AuditActorResolver auditActorResolver;


    public StaffAdminController(
            StaffProvisioningService staffProvisioningService,
            AuditActorResolver auditActorResolver
    ) {
        this.staffProvisioningService = staffProvisioningService;
        this.auditActorResolver = auditActorResolver;
    }


    @GetMapping("/admin/merchants")
    public List<AdminMerchantDto> getMerchants() {
        return staffProvisioningService.getMerchants();
    }


    @PostMapping("/admin/merchants")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminMerchantDto createMerchant(Principal principal, @Valid @RequestBody CreateMerchantBody body) {
        return staffProvisioningService.createMerchant(body, auditActorResolver.resolve(principal));
    }


    @PatchMapping("/admin/merchants/{merchantId}")
    public AdminMerchantDto updateMerchant(
            Principal principal,
            @PathVariable Long merchantId,
            @Valid @RequestBody UpdateStaffStatusBody body
    ) {
        return staffProvisioningService.setMerchantActive(merchantId, body.active(), auditActorResolver.resolve(principal));
    }


    @GetMapping("/admin/riders")
    public List<AdminRiderDto> getRiders() {
        return staffProvisioningService.getRiders();
    }


    @PostMapping("/admin/riders")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminRiderDto createRider(Principal principal, @Valid @RequestBody CreateRiderBody body) {
        return staffProvisioningService.createRider(body, auditActorResolver.resolve(principal));
    }


    @PatchMapping("/admin/riders/{riderId}")
    public AdminRiderDto updateRider(
            Principal principal,
            @PathVariable Long riderId,
            @Valid @RequestBody UpdateRiderStatusBody body
    ) {
        return staffProvisioningService.updateRider(
                riderId, body.active(), body.onDuty(), auditActorResolver.resolve(principal));
    }
}
