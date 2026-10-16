package com.laioffer.onlineorder.staff;


import com.laioffer.onlineorder.audit.AuditActions;
import com.laioffer.onlineorder.audit.AuditActor;
import com.laioffer.onlineorder.audit.AuditLogService;
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
import java.util.LinkedHashMap;
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
 *
 * <p>Every change is written to the audit log with the admin in the same transaction: merchant.create and
 * rider.create here (the login's customer id and email, never the password), merchant.update and rider.update by the
 * module that applies the change, only when something changed. A refused request (409, 404) rolls back and writes
 * none.
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
    private final AuditLogService auditLogService;


    public StaffProvisioningService(
            CustomerService customerService,
            CustomerRepository customerRepository,
            RestaurantService restaurantService,
            MerchantAccountService merchantAccountService,
            RiderService riderService,
            AuditLogService auditLogService
    ) {
        this.customerService = customerService;
        this.customerRepository = customerRepository;
        this.restaurantService = restaurantService;
        this.merchantAccountService = merchantAccountService;
        this.riderService = riderService;
        this.auditLogService = auditLogService;
    }


    /**
     * @throws ResourceNotFoundException                                   when the restaurant does not exist
     * @throws com.laioffer.onlineorder.exception.ConflictException        EMAIL_ALREADY_REGISTERED
     */
    @Transactional
    public AdminMerchantDto createMerchant(CreateMerchantBody body, AuditActor actor) {
        if (!restaurantService.restaurantExists(body.restaurantId())) {
            throw new ResourceNotFoundException("Restaurant not found");
        }
        CustomerEntity login = customerService.createStaffLogin(
                body.email(), body.password(), body.firstName(), body.lastName(), MERCHANT_ROLE);
        MerchantAccountEntity account = merchantAccountService.create(login.id(), body.restaurantId());
        Map<String, Object> details = loginDetails(login);
        details.put("restaurant_id", account.restaurantId());
        auditLogService.record(actor, AuditActions.MERCHANT_CREATE, AuditActions.TARGET_MERCHANT, account.id(), details);
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
    public AdminMerchantDto setMerchantActive(Long merchantId, boolean active, AuditActor actor) {
        MerchantAccountEntity account = merchantAccountService.setActive(merchantId, active, actor);
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
    public AdminRiderDto createRider(CreateRiderBody body, AuditActor actor) {
        CustomerEntity login = customerService.createStaffLogin(
                body.email(), body.password(), body.firstName(), body.lastName(), RIDER_ROLE);
        RiderEntity rider = riderService.create(login.id(), displayName(login), blankToNull(body.phone()));
        auditLogService.record(
                actor, AuditActions.RIDER_CREATE, AuditActions.TARGET_RIDER, rider.id(), loginDetails(login));
        return toRiderDto(rider, Map.of(login.id(), login));
    }


    public List<AdminRiderDto> getRiders() {
        List<RiderEntity> riders = riderService.findAll();
        Map<Long, CustomerEntity> logins = loginsById(riders.stream().map(RiderEntity::customerId));
        return riders.stream().map(rider -> toRiderDto(rider, logins)).toList();
    }


    /**
     * @param active null keeps the flag
     * @param onDuty null keeps the flag (deactivating takes the rider off duty)
     * @throws com.laioffer.onlineorder.exception.ConflictException RIDER_INACTIVE for an inactive rider on duty
     */
    @Transactional
    public AdminRiderDto updateRider(Long riderId, Boolean active, Boolean onDuty, AuditActor actor) {
        RiderEntity rider = riderService.update(riderId, active, onDuty, actor);
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


    /**
     * Who the new login is, for its audit entry: no password, hash or other secret.
     */
    private static Map<String, Object> loginDetails(CustomerEntity login) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("customer_id", login.id());
        details.put("email", login.email());
        return details;
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
