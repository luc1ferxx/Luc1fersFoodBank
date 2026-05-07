package com.laioffer.onlineorder.controller;


import com.laioffer.onlineorder.entity.CustomerEntity;
import com.laioffer.onlineorder.model.AddToCartBody;
import com.laioffer.onlineorder.model.CartDto;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.model.UpdateCartItemBody;
import com.laioffer.onlineorder.service.CartService;
import com.laioffer.onlineorder.service.CustomerService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;


import java.security.Principal;


@RestController
public class CartController {

    private final CartService cartService;
    private final CustomerService customerService;


    public CartController(
            CartService cartService,
            CustomerService customerService
    ) {
        this.cartService = cartService;
        this.customerService = customerService;
    }


    @GetMapping("/cart")
    public CartDto getCart(Principal principal) {
        CustomerEntity customer = customerService.getCustomerByEmail(principal.getName());
        return cartService.getCart(customer.id());
    }


    @PostMapping("/cart")
    public void addToCart(Principal principal, @RequestBody AddToCartBody body) {
        CustomerEntity customer = customerService.getCustomerByEmail(principal.getName());
        cartService.addMenuItemToCart(customer.id(), body.menuId());
    }


    @PutMapping("/cart/items/{orderItemId}")
    public void updateCartItem(
            Principal principal,
            @PathVariable("orderItemId") Long orderItemId,
            @RequestBody UpdateCartItemBody body
    ) {
        CustomerEntity customer = customerService.getCustomerByEmail(principal.getName());
        cartService.updateOrderItemQuantity(customer.id(), orderItemId, body.quantity());
    }


    @DeleteMapping("/cart/items/{orderItemId}")
    public void removeCartItem(
            Principal principal,
            @PathVariable("orderItemId") Long orderItemId
    ) {
        CustomerEntity customer = customerService.getCustomerByEmail(principal.getName());
        cartService.updateOrderItemQuantity(customer.id(), orderItemId, 0);
    }


    @PostMapping("/cart/checkout")
    public OrderDto checkout(
            Principal principal,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        CustomerEntity customer = customerService.getCustomerByEmail(principal.getName());
        return cartService.checkoutWithIdempotency(customer.id(), "PLACED", idempotencyKey);
    }
}
