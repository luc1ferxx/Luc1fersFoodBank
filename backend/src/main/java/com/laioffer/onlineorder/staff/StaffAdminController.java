package com.laioffer.onlineorder.staff;


import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;


import java.util.List;


/**
 * Admin provisioning of merchant and rider logins (ROLE_ADMIN only, see AppConfig; the POSTs and PATCHes are
 * rate-limited per admin like other admin writes). An email that any login already uses is 409
 * EMAIL_ALREADY_REGISTERED; an unknown restaurant, merchant or rider is 404.
 */
@RestController
public class StaffAdminController {

    private final StaffProvisioningService staffProvisioningService;


    public StaffAdminController(StaffProvisioningService staffProvisioningService) {
        this.staffProvisioningService = staffProvisioningService;
    }


    @GetMapping("/admin/merchants")
    public List<AdminMerchantDto> getMerchants() {
        return staffProvisioningService.getMerchants();
    }


    @PostMapping("/admin/merchants")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminMerchantDto createMerchant(@Valid @RequestBody CreateMerchantBody body) {
        return staffProvisioningService.createMerchant(body);
    }


    @PatchMapping("/admin/merchants/{merchantId}")
    public AdminMerchantDto updateMerchant(@PathVariable Long merchantId, @Valid @RequestBody UpdateStaffStatusBody body) {
        return staffProvisioningService.setMerchantActive(merchantId, body.active());
    }


    @GetMapping("/admin/riders")
    public List<AdminRiderDto> getRiders() {
        return staffProvisioningService.getRiders();
    }


    @PostMapping("/admin/riders")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminRiderDto createRider(@Valid @RequestBody CreateRiderBody body) {
        return staffProvisioningService.createRider(body);
    }


    @PatchMapping("/admin/riders/{riderId}")
    public AdminRiderDto updateRider(@PathVariable Long riderId, @Valid @RequestBody UpdateStaffStatusBody body) {
        return staffProvisioningService.setRiderActive(riderId, body.active());
    }
}
