package com.laioffer.onlineorder.fulfilment.dispatch;


import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.service.CustomerService;
import com.laioffer.onlineorder.service.RestaurantService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


import java.security.Principal;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;


/**
 * The rider's own endpoints (ROLE_RIDER only, see AppConfig). A deactivated rider gets 403 on all of them; another
 * rider's delivery is 404. picked-up and delivered are idempotent: repeating one that already happened returns the
 * delivery unchanged, and a delivery that cannot make the move (not picked up yet, cancelled) is 409
 * DELIVERY_STATUS_CONFLICT.
 */
@RestController
public class RiderController {

    private static final Set<DeliveryStatus> OPEN = EnumSet.of(DeliveryStatus.ASSIGNED, DeliveryStatus.PICKED_UP);

    private final CustomerService customerService;
    private final RestaurantService restaurantService;
    private final RiderService riderService;


    public RiderController(CustomerService customerService, RestaurantService restaurantService, RiderService riderService) {
        this.customerService = customerService;
        this.restaurantService = restaurantService;
        this.riderService = riderService;
    }


    @GetMapping("/rider/me")
    public RiderProfileDto getProfile(Principal principal) {
        return new RiderProfileDto(riderService.requireActiveRider(customerId(principal)));
    }


    @PutMapping("/rider/me/availability")
    public RiderProfileDto setAvailability(Principal principal, @Valid @RequestBody RiderAvailabilityBody body) {
        return new RiderProfileDto(riderService.setOnDuty(customerId(principal), body.onDuty()));
    }


    /**
     * @param status one or more delivery statuses (repeated or comma-separated); the open deliveries (ASSIGNED and
     *               PICKED_UP) when absent
     */
    @GetMapping("/rider/deliveries")
    public List<RiderDeliveryDto> getDeliveries(
            Principal principal,
            @RequestParam(required = false) List<String> status,
            @RequestParam(defaultValue = "50") int limit
    ) {
        List<DeliveryEntity> deliveries = riderService.getDeliveries(customerId(principal), statuses(status), limit);
        Map<Long, String> restaurantNames = restaurantService.findNames(
                deliveries.stream().map(DeliveryEntity::restaurantId).toList());
        return deliveries.stream()
                .map(delivery -> new RiderDeliveryDto(delivery, restaurantNames.get(delivery.restaurantId())))
                .toList();
    }


    @PostMapping("/rider/deliveries/{deliveryId:\\d+}/picked-up")
    public RiderDeliveryDto markPickedUp(Principal principal, @PathVariable Long deliveryId) {
        return withRestaurantName(riderService.markPickedUp(customerId(principal), deliveryId));
    }


    @PostMapping("/rider/deliveries/{deliveryId:\\d+}/delivered")
    public RiderDeliveryDto markDelivered(Principal principal, @PathVariable Long deliveryId) {
        return withRestaurantName(riderService.markDelivered(customerId(principal), deliveryId));
    }


    private RiderDeliveryDto withRestaurantName(DeliveryEntity delivery) {
        return new RiderDeliveryDto(
                delivery,
                restaurantService.findNames(List.of(delivery.restaurantId())).get(delivery.restaurantId())
        );
    }


    private static Set<DeliveryStatus> statuses(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return OPEN;
        }
        Set<DeliveryStatus> statuses = EnumSet.noneOf(DeliveryStatus.class);
        for (String value : requested) {
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                statuses.add(DeliveryStatus.valueOf(value.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                throw new BadRequestException("status must be one of ASSIGNED, PICKED_UP, DELIVERED, CANCELLED");
            }
        }
        return statuses.isEmpty() ? OPEN : statuses;
    }


    /**
     * The logged-in rider's customer id, resolved like every other controller resolves its principal.
     */
    private Long customerId(Principal principal) {
        return customerService.getCustomerByEmail(principal.getName()).id();
    }
}
