package com.laioffer.onlineorder.ids;


import com.laioffer.onlineorder.error.ApiErrorResponseFactory;
import com.laioffer.onlineorder.error.ApiExceptionHandler;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.partition.PartitionCatalog;
import com.laioffer.onlineorder.partition.PartitionMaintenanceService;
import com.laioffer.onlineorder.partition.PartitionedTable;
import com.laioffer.onlineorder.service.CartService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;


import java.time.LocalDateTime;


/**
 * The h2 profile: a static worker id (0), plain tables and no partition maintenance.
 */
@SpringBootTest
@ActiveProfiles("h2")
class OrderNumberH2IntegrationTests {

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.cache.type", () -> "none");
        registry.add("app.kafka.enabled", () -> "false");
        registry.add("app.security.rate-limit.enabled", () -> "false");
        registry.add("app.ids.worker-id", () -> "11");
    }

    @Autowired
    private DistributedIdGenerator idGenerator;

    @Autowired
    private CartService cartService;

    @Autowired
    private PartitionCatalog partitionCatalog;

    @Autowired
    private PartitionMaintenanceService partitionMaintenanceService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;


    @Test
    void checkout_shouldNumberTheOrderWithTheConfiguredWorkerId() {
        Assertions.assertInstanceOf(StaticWorkerIdGenerator.class, idGenerator);
        Long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM customers WHERE email = 'demo@laifood.com'", Long.class);
        cartService.clearCart(customerId);
        cartService.addMenuItemToCart(customerId, 1L);

        OrderDto order = cartService.checkout(customerId);

        Assertions.assertEquals(11, SnowflakeIdGenerator.workerIdOf(order.orderNo()));
        Assertions.assertEquals(11.0, meterRegistry.get("onlineorder.ids.worker_id").gauge().value());
    }


    @Test
    void partitionMaintenance_shouldDoNothingOnH2() {
        for (PartitionedTable table : PartitionedTable.values()) {
            Assertions.assertFalse(partitionCatalog.isPartitioned(table));
        }
        Assertions.assertFalse(partitionCatalog.isPostgres());

        Assertions.assertDoesNotThrow(() -> partitionMaintenanceService.maintainPartitions(LocalDateTime.now()));
        Assertions.assertTrue(Double.isNaN(meterRegistry.get("onlineorder.partitions.future")
                .tag("table", "processed_events").gauge().value()));
    }


    @Test
    void anUnavailableIdGenerator_shouldAnswer503() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new FailingCheckoutController())
                .setControllerAdvice(new ApiExceptionHandler(new ApiErrorResponseFactory()))
                .build();

        mockMvc.perform(MockMvcRequestBuilders.get("/probe/order-number"))
                .andExpect(MockMvcResultMatchers.status().isServiceUnavailable())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }


    @RestController
    static class FailingCheckoutController {

        @GetMapping("/probe/order-number")
        String orderNumber() {
            throw new ClockMovedBackwardsException(0, 5_000, 50);
        }
    }
}
