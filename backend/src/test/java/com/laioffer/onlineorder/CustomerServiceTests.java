package com.laioffer.onlineorder;


import com.laioffer.onlineorder.entity.CartEntity;
import com.laioffer.onlineorder.entity.CustomerEntity;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.repository.CartRepository;
import com.laioffer.onlineorder.repository.CustomerRepository;
import com.laioffer.onlineorder.service.CustomerService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.UserDetailsManager;


import java.math.BigDecimal;


@ExtendWith(MockitoExtension.class)
class CustomerServiceTests {

    @Mock
    private CartRepository cartRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private UserDetailsManager userDetailsManager;

    @Mock
    private PasswordEncoder passwordEncoder;

    private CustomerService customerService;


    @BeforeEach
    void setup() {
        Mockito.lenient().when(passwordEncoder.encode(Mockito.anyString())).thenReturn("encoded-password");
        customerService = new CustomerService(
                cartRepository,
                customerRepository,
                passwordEncoder,
                userDetailsManager
        );
    }


    @Test
    void signUp_shouldCreateCustomerAndCart() {
        CustomerEntity customer = new CustomerEntity(
                7L,
                "user@example.com",
                "password",
                true,
                "Ada",
                "Lovelace",
                "ACTIVE",
                true,
                0,
                null,
                null,
                null,
                null
        );
        Mockito.when(customerRepository.findByEmail("user@example.com")).thenReturn(customer);

        customerService.signUp("User@Example.com", "password", "Ada", "Lovelace");

        Mockito.verify(userDetailsManager).createUser(Mockito.argThat(userDetails ->
                userDetails.getUsername().equals("user@example.com")
        ));
        Mockito.verify(customerRepository).updateNameByEmail("user@example.com", "Ada", "Lovelace");
        Mockito.verify(cartRepository).save(new CartEntity(null, 7L, BigDecimal.ZERO));
    }


    @Test
    void signUp_whenEmailAlreadyExists_shouldThrowConflict() {
        Mockito.doThrow(new DataIntegrityViolationException("duplicate"))
                .when(userDetailsManager)
                .createUser(Mockito.any());

        Assertions.assertThrows(ConflictException.class, () ->
                customerService.signUp("user@example.com", "password", "Ada", "Lovelace")
        );

        Mockito.verifyNoInteractions(cartRepository);
    }


    @Test
    void createStaffLogin_shouldCreateAnEncodedLoginWithOnlyTheStaffRoleAndNoCart() {
        CustomerEntity login = new CustomerEntity(
                9L, "rider@example.com", "encoded-password", true, "Dana", "Rider", "ACTIVE", true, 0,
                null, null, null, null);
        Mockito.when(customerRepository.findByEmail("rider@example.com")).thenReturn(login);

        CustomerEntity created = customerService.createStaffLogin(" Rider@Example.com ", "secret", "Dana", "Rider", "RIDER");

        Assertions.assertEquals(9L, created.id());
        Mockito.verify(customerRepository).countByEmailIgnoreCase("rider@example.com");
        Mockito.verify(userDetailsManager).createUser(Mockito.argThat(userDetails ->
                userDetails.getUsername().equals("rider@example.com")
                        && userDetails.getPassword().equals("encoded-password")
                        && userDetails.getAuthorities().size() == 1
                        && userDetails.getAuthorities().iterator().next().getAuthority().equals("ROLE_RIDER")
        ));
        Mockito.verify(customerRepository).updateNameByEmail("rider@example.com", "Dana", "Rider");
        Mockito.verifyNoInteractions(cartRepository);
    }


    @Test
    void createStaffLogin_whenTheEmailIsTaken_shouldConflictWithEmailAlreadyRegistered() {
        Mockito.when(customerRepository.countByEmailIgnoreCase("taken@example.com")).thenReturn(1L, 0L);
        Mockito.doThrow(new DataIntegrityViolationException("duplicate"))
                .when(userDetailsManager)
                .createUser(Mockito.any());

        // Found by the check, and lost to a concurrent registration after the check.
        for (int attempt = 0; attempt < 2; attempt++) {
            ConflictException conflict = Assertions.assertThrows(ConflictException.class, () ->
                    customerService.createStaffLogin("taken@example.com", "secret", null, null, "MERCHANT"));
            Assertions.assertEquals(CustomerService.EMAIL_ALREADY_REGISTERED, conflict.getCode());
        }
        Mockito.verify(userDetailsManager, Mockito.times(1)).createUser(Mockito.any());
        Mockito.verify(customerRepository, Mockito.never())
                .updateNameByEmail(Mockito.anyString(), Mockito.any(), Mockito.any());
    }


    @Test
    void recordFailedLoginAttempt_shouldIncrementFailureCountAndLockAccountAtThreshold() {
        customerService.recordFailedLoginAttempt("user@example.com");

        Mockito.verify(customerRepository).recordFailedLoginAttempt(Mockito.eq("user@example.com"), Mockito.any(), Mockito.eq(5));
    }


    @Test
    void recordSuccessfulLogin_shouldResetFailuresAndSetLastLoginTimestamp() {
        customerService.recordSuccessfulLogin("user@example.com");

        Mockito.verify(customerRepository).recordSuccessfulLogin(Mockito.eq("user@example.com"), Mockito.any());
    }
}
