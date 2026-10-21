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


    @Test
    void afterSalesRefundMetric_shouldCountEachOutcome() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ApplicationMetricsService metricsService = new ApplicationMetricsService(meterRegistry);

        metricsService.recordAfterSalesRefund("succeeded");
        metricsService.recordAfterSalesRefund("succeeded");
        metricsService.recordAfterSalesRefund("superseded");

        Assertions.assertEquals(2.0, meterRegistry.counter("onlineorder.aftersales.refunds", "outcome", "succeeded")
                .count());
        Assertions.assertEquals(1.0, meterRegistry.counter("onlineorder.aftersales.refunds", "outcome", "superseded")
                .count());
    }


    @Test
    void afterSalesUndercapturedCancellationMetric_shouldCountEachOpening() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ApplicationMetricsService metricsService = new ApplicationMetricsService(meterRegistry);

        metricsService.recordAfterSalesUndercapturedCancellation();
        metricsService.recordAfterSalesUndercapturedCancellation();

        Assertions.assertEquals(2.0, meterRegistry.counter("onlineorder.aftersales.cancellations.undercaptured")
                .count());
    }
}
