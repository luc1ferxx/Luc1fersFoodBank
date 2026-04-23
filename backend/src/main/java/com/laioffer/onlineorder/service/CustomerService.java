package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.entity.CartEntity;
import com.laioffer.onlineorder.entity.CustomerEntity;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.repository.CartRepository;
import com.laioffer.onlineorder.repository.CustomerRepository;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;


@Service
public class CustomerService {


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
            CartEntity cart = new CartEntity(null, savedCustomer.id(), 0.0);
            cartRepository.save(cart);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("Email already exists");
        }
    }


    public CustomerEntity getCustomerByEmail(String email) {
        CustomerEntity customer = customerRepository.findByEmail(email.toLowerCase());
        if (customer == null) {
            throw new ResourceNotFoundException("Customer not found");
        }
        return customer;
    }
}
