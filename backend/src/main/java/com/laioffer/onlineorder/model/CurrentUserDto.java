package com.laioffer.onlineorder.model;


import com.laioffer.onlineorder.entity.CustomerEntity;


public record CurrentUserDto(
        Long id,
        String email,
        String firstName,
        String lastName
) {

    public CurrentUserDto(CustomerEntity customer) {
        this(customer.id(), customer.email(), customer.firstName(), customer.lastName());
    }
}
