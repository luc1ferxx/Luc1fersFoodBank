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
        Mockito.when(passwordEncoder.encode(Mockito.anyString())).thenReturn("encoded-password");
        customerService = new CustomerService(
                cartRepository,
                customerRepository,
                passwordEncoder,
                userDetailsManager
        );
    }


    @Test
    void signUp_shouldCreateCustomerAndCart() {
        CustomerEntity customer = new CustomerEntity(7L, "user@example.com", "password", true, "Ada", "Lovelace");
        Mockito.when(customerRepository.findByEmail("user@example.com")).thenReturn(customer);

        customerService.signUp("User@Example.com", "password", "Ada", "Lovelace");

        Mockito.verify(userDetailsManager).createUser(Mockito.argThat(userDetails ->
                userDetails.getUsername().equals("user@example.com")
        ));
        Mockito.verify(customerRepository).updateNameByEmail("user@example.com", "Ada", "Lovelace");
        Mockito.verify(cartRepository).save(new CartEntity(null, 7L, 0.0));
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
}
