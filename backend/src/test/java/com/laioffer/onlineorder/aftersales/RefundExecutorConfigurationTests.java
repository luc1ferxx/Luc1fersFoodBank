package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.money.Money;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.repository.OrderPaymentRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;


import java.time.Duration;


/**
 * The refund executor's settings as application.yml binds them, and section 19.10: a gateway timeout that is not
 * shorter than the claim's lease fails startup, since another executor would reclaim a refund whose call may still
 * succeed.
 */
class RefundExecutorConfigurationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            // SpringApplication registers this so @Value can bind durations such as 10s.
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withBean(RefundRepository.class, () -> Mockito.mock(RefundRepository.class))
            .withBean(AfterSalesCaseRepository.class, () -> Mockito.mock(AfterSalesCaseRepository.class))
            .withBean(OrderPaymentRepository.class, () -> Mockito.mock(OrderPaymentRepository.class))
            .withBean(AfterSalesTransitions.class, () -> Mockito.mock(AfterSalesTransitions.class))
            .withBean(ApplicationMetricsService.class, () -> Mockito.mock(ApplicationMetricsService.class))
            .withBean(PlatformTransactionManager.class, () -> Mockito.mock(PlatformTransactionManager.class))
            .withUserConfiguration(SimulatedRefundGateway.class, RefundExecutor.class);


    @Test
    void applicationYml_shouldBindTheDocumentedDefaults() {
        contextRunner.run(context -> {
            Assertions.assertNull(context.getStartupFailure());
            Assertions.assertEquals(
                    new RefundExecutor.Settings(100, 8, Duration.ofSeconds(10), Duration.ofMinutes(10),
                            Duration.ofSeconds(60), Duration.ofSeconds(10), 4),
                    context.getBean(RefundExecutor.class).settings()
            );
            // The simulated gateway fails no attempt by default.
            Assertions.assertInstanceOf(RefundGatewayResult.Succeeded.class, context.getBean(RefundGateway.class)
                    .refund(new RefundGatewayRequest("after-sales-case:1", 1L, Money.of("1.00", "USD"), 1, "P")));
        });
    }


    @Test
    void environmentVariables_shouldOverrideTheSettings() {
        contextRunner
                .withPropertyValues(
                        "AFTER_SALES_POLL_BATCH_SIZE=7",
                        "AFTER_SALES_REFUND_MAX_ATTEMPTS=3",
                        "AFTER_SALES_REFUND_RETRY_BACKOFF=2s",
                        "AFTER_SALES_REFUND_RETRY_BACKOFF_MAX=1m",
                        "AFTER_SALES_REFUND_CLAIM_TIMEOUT=2m",
                        "AFTER_SALES_REFUND_GATEWAY_TIMEOUT=30s",
                        "AFTER_SALES_REFUND_GATEWAY_FAIL_FIRST_ATTEMPTS=1",
                        "AFTER_SALES_REFUND_GATEWAY_MAX_THREADS=2"
                )
                .run(context -> {
                    Assertions.assertEquals(
                            new RefundExecutor.Settings(7, 3, Duration.ofSeconds(2), Duration.ofMinutes(1),
                                    Duration.ofMinutes(2), Duration.ofSeconds(30), 2),
                            context.getBean(RefundExecutor.class).settings()
                    );
                    Assertions.assertInstanceOf(RefundGatewayResult.RetryableFailure.class,
                            context.getBean(RefundGateway.class).refund(new RefundGatewayRequest(
                                    "after-sales-case:1", 1L, Money.of("1.00", "USD"), 1, "P")));
                });
    }


    @Test
    void gatewayTimeoutNotShorterThanTheLease_shouldFailStartup() {
        for (String gatewayTimeout : new String[] {"60s", "90s"}) {
            contextRunner
                    .withPropertyValues("app.after-sales.refund-gateway.timeout=" + gatewayTimeout)
                    .run(context -> {
                        Throwable failure = context.getStartupFailure();
                        Assertions.assertNotNull(failure, gatewayTimeout);
                        Throwable cause = rootCause(failure);
                        Assertions.assertInstanceOf(IllegalArgumentException.class, cause);
                        Assertions.assertTrue(cause.getMessage().startsWith(
                                "app.after-sales.refund-gateway.timeout (" + Duration.parse("PT"
                                        + gatewayTimeout.toUpperCase(java.util.Locale.ROOT)) + ") must be shorter than "
                                        + "app.after-sales.refund.claim-timeout (PT1M)"), cause.getMessage());
                    });
        }
        // A shorter lease with the default gateway timeout fails the same way.
        contextRunner.withPropertyValues("AFTER_SALES_REFUND_CLAIM_TIMEOUT=5s").run(context ->
                Assertions.assertInstanceOf(IllegalArgumentException.class, rootCause(context.getStartupFailure())));
    }


    /**
     * Section 24: the gateway calls need one thread at least.
     */
    @Test
    void noGatewayThread_shouldFailStartup() {
        contextRunner.withPropertyValues("AFTER_SALES_REFUND_GATEWAY_MAX_THREADS=0").run(context -> {
            Throwable cause = rootCause(context.getStartupFailure());
            Assertions.assertInstanceOf(IllegalArgumentException.class, cause);
            Assertions.assertEquals("app.after-sales.refund-gateway.max-threads must be at least 1, got 0",
                    cause.getMessage());
        });
    }


    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }
}
