package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.entity.CartEntity;
import com.laioffer.onlineorder.entity.CustomerEntity;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.money.Money;
import com.laioffer.onlineorder.repository.CartRepository;
import com.laioffer.onlineorder.repository.CustomerRepository;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;


import java.time.LocalDateTime;


@Service
public class CustomerService {

    public static final String EMAIL_ALREADY_REGISTERED = "EMAIL_ALREADY_REGISTERED";

    private static final int LOGIN_LOCK_THRESHOLD = 5;
    private static final long LOGIN_LOCK_MINUTES = 15;


    private final CartRepository cartRepository;
    private final CustomerRepository customerRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserDetailsManager userDetailsManager;


    public CustomerService(
            CartRepository cartRepository,
            CustomerRepository customerRepository,
            PasswordEncoder passwordEncoder,
            UserDetailsManager userDetailsManager) {
        this.cartRepository = cartRepository;
        this.customerRepository = customerRepository;
        this.passwordEncoder = passwordEncoder;
        this.userDetailsManager = userDetailsManager;
    }


    @Transactional
    public void signUp(String email, String password, String firstName, String lastName) {
        email = email.toLowerCase();
        UserDetails user = User.builder()
                .username(email)
                .password(passwordEncoder.encode(password))
                .roles("USER")
                .build();
        try {
            userDetailsManager.createUser(user);
            customerRepository.updateNameByEmail(email, firstName, lastName);

            CustomerEntity savedCustomer = customerRepository.findByEmail(email);
            if (savedCustomer == null) {
                throw new ConflictException("Unable to create customer profile");
            }
            Money emptyTotal = Money.zero(Money.LEGACY_CURRENCY);
            cartRepository.save(new CartEntity(null, savedCustomer.id(), emptyTotal.amount(), emptyTotal.currencyCode()));
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("Email already exists");
        }
    }


    /**
     * Creates a staff login (a merchant or a rider, provisioned by an admin) with the same password encoding and
     * lifecycle defaults as {@link #signUp}, but with {@code role} as its only authority and no cart. Runs in the
     * caller's transaction, so a failure of the caller's next step (binding the login to a restaurant or a rider)
     * removes the login again.
     *
     * @param role the role without its prefix, for example MERCHANT
     * @throws ConflictException EMAIL_ALREADY_REGISTERED when any login uses this email, in any letter case
     */
    @Transactional
    public CustomerEntity createStaffLogin(String email, String password, String firstName, String lastName, String role) {
        String normalizedEmail = email.trim().toLowerCase();
        if (customerRepository.countByEmailIgnoreCase(normalizedEmail) > 0) {
            throw new ConflictException(EMAIL_ALREADY_REGISTERED, "Email is already registered");
        }
        UserDetails user = User.builder()
                .username(normalizedEmail)
                .password(passwordEncoder.encode(password))
                .roles(role)
                .build();
        try {
            userDetailsManager.createUser(user);
        } catch (DataIntegrityViolationException ex) {
            // A concurrent request registered the same email after the check above.
            throw new ConflictException(EMAIL_ALREADY_REGISTERED, "Email is already registered");
        }
        customerRepository.updateNameByEmail(normalizedEmail, firstName, lastName);
        CustomerEntity customer = customerRepository.findByEmail(normalizedEmail);
        if (customer == null) {
            throw new IllegalStateException("Staff login " + normalizedEmail + " is missing after its insert");
        }
        return customer;
    }


    public CustomerEntity getCustomerByEmail(String email) {
        CustomerEntity customer = customerRepository.findByEmail(email.toLowerCase());
        if (customer == null) {
            throw new ResourceNotFoundException("Customer not found");
        }
        return customer;
    }


    @Transactional
    public void recordFailedLoginAttempt(String email) {
        if (email == null || email.isBlank()) {
            return;
        }
        customerRepository.recordFailedLoginAttempt(
                email.trim().toLowerCase(),
                LocalDateTime.now().plusMinutes(LOGIN_LOCK_MINUTES),
                LOGIN_LOCK_THRESHOLD
        );
    }


    @Transactional
    public void recordSuccessfulLogin(String email) {
        if (email == null || email.isBlank()) {
            return;
        }
        customerRepository.recordSuccessfulLogin(email.trim().toLowerCase(), LocalDateTime.now());
    }
}
