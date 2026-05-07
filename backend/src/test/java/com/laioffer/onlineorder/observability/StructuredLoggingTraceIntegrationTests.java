package com.laioffer.onlineorder.observability;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;


@SpringBootTest
@ActiveProfiles("h2")
@ExtendWith(OutputCaptureExtension.class)
class StructuredLoggingTraceIntegrationTests {

    private static final Logger LOGGER = LoggerFactory.getLogger(StructuredLoggingTraceIntegrationTests.class);


    @Test
    void structuredJsonLogs_shouldIncludeMdcTraceId(CapturedOutput output) {
        MDC.put("traceId", "trace-json-12345");
        try {
            LOGGER.info("structured trace correlation test event");
        } finally {
            MDC.remove("traceId");
        }

        Assertions.assertTrue(output.getOut().contains("structured trace correlation test event"));
        Assertions.assertTrue(output.getOut().contains("trace-json-12345"));
    }
}
