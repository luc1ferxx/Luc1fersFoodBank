package com.laioffer.onlineorder.controller;


import com.laioffer.onlineorder.entity.CustomerEntity;
import com.laioffer.onlineorder.model.CurrentUserDto;
import com.laioffer.onlineorder.service.CustomerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;


@RestController
public class SessionController {

    private final CustomerService customerService;


    public SessionController(CustomerService customerService) {
        this.customerService = customerService;
    }


    @GetMapping("/me")
    public CurrentUserDto getCurrentUser(Principal principal) {
        CustomerEntity customer = customerService.getCustomerByEmail(principal.getName());
        return new CurrentUserDto(customer);
    }
}
