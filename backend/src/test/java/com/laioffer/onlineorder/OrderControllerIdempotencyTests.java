package com.laioffer.onlineorder;

import com.laioffer.onlineorder.controller.OrderController;
import com.laioffer.onlineorder.entity.CustomerEntity;
import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.service.CustomerService;
import com.laioffer.onlineorder.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.Principal;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class OrderControllerIdempotencyTests {

    @Mock
    private CustomerService customerService;

    @Mock
    private OrderService orderService;

    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.standaloneSetup(new OrderController(customerService, orderService)).build();
    }

    @Test
    void updateOrderStatus_whenIdempotencyKeyHeaderIsMissing_shouldReturnBadRequestBeforeCreatingIdempotencyRecord() throws Exception {
        mockMvc.perform(patch("/orders/5/status")
                        .principal(principal())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACCEPTED\"}"))
                .andExpect(status().isBadRequest());

        Mockito.verifyNoInteractions(customerService, orderService);
    }

    @Test
    void cancelOrder_whenIdempotencyKeyHeaderIsMissing_shouldReturnBadRequestBeforeCreatingIdempotencyRecord() throws Exception {
        mockMvc.perform(post("/orders/5/cancel")
                        .principal(principal()))
                .andExpect(status().isBadRequest());

        Mockito.verifyNoInteractions(customerService, orderService);
    }

    @Test
    void updateOrderStatus_whenIdempotencyKeyHeaderIsBlank_shouldReturnBadRequestFromServiceValidation() throws Exception {
        CustomerEntity actor = actor();
        Mockito.when(customerService.getCustomerByEmail("admin@example.com")).thenReturn(actor);
        Mockito.when(orderService.idempotentTransitionOrderStatus(actor.id(), 5L, "ACCEPTED", "   "))
                .thenThrow(new BadRequestException("Idempotency-Key header is required"));

        mockMvc.perform(patch("/orders/5/status")
                        .principal(principal())
                        .header("Idempotency-Key", "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACCEPTED\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateOrderStatus_whenIdempotencyKeyHeaderIsTooLong_shouldReturnBadRequestFromServiceValidation() throws Exception {
        CustomerEntity actor = actor();
        String tooLongKey = "x".repeat(256);
        Mockito.when(customerService.getCustomerByEmail("admin@example.com")).thenReturn(actor);
        Mockito.when(orderService.idempotentTransitionOrderStatus(actor.id(), 5L, "ACCEPTED", tooLongKey))
                .thenThrow(new BadRequestException("Idempotency-Key header must be 255 characters or fewer"));

        mockMvc.perform(patch("/orders/5/status")
                        .principal(principal())
                        .header("Idempotency-Key", tooLongKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACCEPTED\"}"))
                .andExpect(status().isBadRequest());
    }

    private Principal principal() {
        return () -> "admin@example.com";
    }

    private CustomerEntity actor() {
        LocalDateTime now = LocalDateTime.of(2026, 4, 4, 12, 0);
        return new CustomerEntity(
                7L,
                "admin@example.com",
                "{noop}demo123",
                true,
                "Admin",
                "User",
                "ACTIVE",
                true,
                0,
                null,
                null,
                now,
                now
        );
    }
}
