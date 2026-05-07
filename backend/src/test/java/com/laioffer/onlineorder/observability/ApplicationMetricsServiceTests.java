package com.laioffer.onlineorder.observability;


import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


class ApplicationMetricsServiceTests {

    @Test
    void checkoutMetrics_shouldRecordSuccessAndFailureResults() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ApplicationMetricsService metricsService = new ApplicationMetricsService(meterRegistry);

        metricsService.recordCheckoutSuccess("PAID");
        metricsService.recordCheckoutFailure("BadRequestException");

        Assertions.assertEquals(1.0, meterRegistry.counter(
                "onlineorder.checkout.requests",
                "result", "success",
                "status", "paid"
        ).count());
        Assertions.assertEquals(1.0, meterRegistry.counter(
                "onlineorder.checkout.requests",
                "result", "failure",
                "reason", "badrequestexception"
        ).count());
    }
}
