package com.laioffer.onlineorder.staff;


import com.laioffer.onlineorder.entity.CustomerEntity;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.fulfilment.dispatch.RiderEntity;
import com.laioffer.onlineorder.fulfilment.dispatch.RiderService;
import com.laioffer.onlineorder.fulfilment.merchant.MerchantAccountEntity;
import com.laioffer.onlineorder.fulfilment.merchant.MerchantAccountService;
import com.laioffer.onlineorder.model.CurrentUserDto;
import com.laioffer.onlineorder.repository.CustomerRepository;
import com.laioffer.onlineorder.service.CustomerService;
import com.laioffer.onlineorder.service.RestaurantService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;


/**
 * Admin provisioning of merchant and rider logins, and the staff part of GET /me.
 *
 * <p>This is admin web-layer orchestration, not a fulfilment module: in one transaction it creates the login through
 * {@link CustomerService} (BCrypt password through the shared PasswordEncoder, the same email and password validation
 * as signup) and binds it through the merchant module's {@link MerchantAccountService} or the dispatch module's
 * {@link RiderService}. A failed bind removes the login again. The fulfilment modules themselves still only read and
 * write their own tables and never call each other.
 *
 * <p>Accounts are never deleted: an admin deactivates them, which takes effect on the account's next request because
 * the modules re-check the active flag on every merchant and rider request.
 */
@Service
public class StaffProvisioningService {

    static final String MERCHANT_ROLE = "MERCHANT";
    static final String RIDER_ROLE = "RIDER";

    private final CustomerService customerService;
    private final CustomerRepository customerRepository;
    private final RestaurantService restaurantService;
    private final MerchantAccountService merchantAccountService;
    private final RiderService riderService;


    public StaffProvisioningService(
            CustomerService customerService,
            CustomerRepository customerRepository,
            RestaurantService restaurantService,
            MerchantAccountService merchantAccountService,
            RiderService riderService
    ) {
        this.customerService = customerService;
        this.customerRepository = customerRepository;
        this.restaurantService = restaurantService;
        this.merchantAccountService = merchantAccountService;
        this.riderService = riderService;
    }


    /**
     * @throws ResourceNotFoundException                                   when the restaurant does not exist
     * @throws com.laioffer.onlineorder.exception.ConflictException        EMAIL_ALREADY_REGISTERED
     */
    @Transactional
    public AdminMerchantDto createMerchant(CreateMerchantBody body) {
        if (!restaurantService.restaurantExists(body.restaurantId())) {
            throw new ResourceNotFoundException("Restaurant not found");
        }
        CustomerEntity login = customerService.createStaffLogin(
                body.email(), body.password(), body.firstName(), body.lastName(), MERCHANT_ROLE);
        MerchantAccountEntity account = merchantAccountService.create(login.id(), body.restaurantId());
        return toMerchantDto(account, Map.of(login.id(), login), restaurantService.findNames(List.of(account.restaurantId())));
    }


    public List<AdminMerchantDto> getMerchants() {
        List<MerchantAccountEntity> accounts = merchantAccountService.findAll();
        Map<Long, CustomerEntity> logins = loginsById(accounts.stream().map(MerchantAccountEntity::customerId));
        Map<Long, String> restaurantNames = restaurantService.findNames(
                accounts.stream().map(MerchantAccountEntity::restaurantId).collect(Collectors.toSet()));
        return accounts.stream().map(account -> toMerchantDto(account, logins, restaurantNames)).toList();
    }


    @Transactional
    public AdminMerchantDto setMerchantActive(Long merchantId, boolean active) {
        MerchantAccountEntity account = merchantAccountService.setActive(merchantId, active);
        return toMerchantDto(
                account,
                loginsById(Stream.of(account.customerId())),
                restaurantService.findNames(List.of(account.restaurantId()))
        );
    }


    /**
     * @throws com.laioffer.onlineorder.exception.ConflictException EMAIL_ALREADY_REGISTERED
     */
    @Transactional
    public AdminRiderDto createRider(CreateRiderBody body) {
        CustomerEntity login = customerService.createStaffLogin(
                body.email(), body.password(), body.firstName(), body.lastName(), RIDER_ROLE);
        RiderEntity rider = riderService.create(login.id(), displayName(login), blankToNull(body.phone()));
        return toRiderDto(rider, Map.of(login.id(), login));
    }


    public List<AdminRiderDto> getRiders() {
        List<RiderEntity> riders = riderService.findAll();
        Map<Long, CustomerEntity> logins = loginsById(riders.stream().map(RiderEntity::customerId));
        return riders.stream().map(rider -> toRiderDto(rider, logins)).toList();
    }


    @Transactional
    public AdminRiderDto setRiderActive(Long riderId, boolean active) {
        RiderEntity rider = riderService.setActive(riderId, active);
        return toRiderDto(rider, loginsById(Stream.of(rider.customerId())));
    }


    public Optional<CurrentUserDto.Merchant> merchantOf(Long customerId) {
        return merchantAccountService.findByCustomerId(customerId).map(account -> new CurrentUserDto.Merchant(
                account.id(),
                account.restaurantId(),
                restaurantService.findNames(List.of(account.restaurantId())).get(account.restaurantId()),
                account.active()
        ));
    }


    public Optional<CurrentUserDto.Rider> riderOf(Long customerId) {
        return riderService.findByCustomerId(customerId)
                .map(rider -> new CurrentUserDto.Rider(rider.id(), rider.name(), rider.onDuty(), rider.active()));
    }


    private Map<Long, CustomerEntity> loginsById(Stream<Long> customerIds) {
        Collection<Long> ids = customerIds.filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, CustomerEntity> logins = new HashMap<>();
        for (CustomerEntity customer : customerRepository.findAllById(ids)) {
            logins.put(customer.id(), customer);
        }
        return logins;
    }


    private static AdminMerchantDto toMerchantDto(
            MerchantAccountEntity account,
            Map<Long, CustomerEntity> logins,
            Map<Long, String> restaurantNames
    ) {
        CustomerEntity login = logins.get(account.customerId());
        return new AdminMerchantDto(
                account.id(),
                account.customerId(),
                login == null ? null : login.email(),
                login == null ? null : login.firstName(),
                login == null ? null : login.lastName(),
                account.restaurantId(),
                restaurantNames.get(account.restaurantId()),
                account.active(),
                account.createdAt()
        );
    }


    private static AdminRiderDto toRiderDto(RiderEntity rider, Map<Long, CustomerEntity> logins) {
        CustomerEntity login = rider.customerId() == null ? null : logins.get(rider.customerId());
        return new AdminRiderDto(
                rider.id(),
                rider.customerId(),
                login == null ? null : login.email(),
                login == null ? null : login.firstName(),
                login == null ? null : login.lastName(),
                rider.name(),
                rider.phone(),
                rider.active(),
                rider.onDuty(),
                rider.activeDeliveries(),
                rider.createdAt()
        );
    }


    static String displayName(CustomerEntity login) {
        String name = Stream.of(login.firstName(), login.lastName())
                .filter(part -> part != null && !part.isBlank())
                .map(String::trim)
                .collect(Collectors.joining(" "));
        return name.isEmpty() ? login.email() : name;
    }


    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
