package com.laioffer.onlineorder.observability;


import com.laioffer.onlineorder.repository.DeadLetterEventRepository;
import com.laioffer.onlineorder.repository.OutboxEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;


class OperationalMetricsBinderTests {

    @Test
    void bindTo_shouldExposeOutboxBacklogFailedAndStatusGauges() {
        OutboxEventRepository outboxEventRepository = Mockito.mock(OutboxEventRepository.class);
        DeadLetterEventRepository deadLetterEventRepository = Mockito.mock(DeadLetterEventRepository.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        Mockito.when(outboxEventRepository.countByStatus("PENDING")).thenReturn(4L);
        Mockito.when(outboxEventRepository.countByStatus("PROCESSING")).thenReturn(2L);
        Mockito.when(outboxEventRepository.countByStatus("FAILED")).thenReturn(3L);
        Mockito.when(outboxEventRepository.countByStatus("PUBLISHED")).thenReturn(11L);
        Mockito.when(outboxEventRepository.countByStatuses("PENDING", "PROCESSING")).thenReturn(6L);
        Mockito.when(deadLetterEventRepository.countByReplayStatuses("PENDING", "REPLAY_FAILED", "REPLAYED")).thenReturn(5L);
        Mockito.when(deadLetterEventRepository.countByReplayStatus("PENDING")).thenReturn(2L);
        Mockito.when(deadLetterEventRepository.countByReplayStatus("REPLAY_FAILED")).thenReturn(1L);
        Mockito.when(deadLetterEventRepository.countByReplayStatus("REPLAYED")).thenReturn(2L);

        new OperationalMetricsBinder(outboxEventRepository, deadLetterEventRepository).bindTo(meterRegistry);

        Assertions.assertEquals(4.0, meterRegistry.get("onlineorder.outbox.events").tag("status", "pending").gauge().value());
        Assertions.assertEquals(2.0, meterRegistry.get("onlineorder.outbox.events").tag("status", "processing").gauge().value());
        Assertions.assertEquals(3.0, meterRegistry.get("onlineorder.outbox.events").tag("status", "failed").gauge().value());
        Assertions.assertEquals(11.0, meterRegistry.get("onlineorder.outbox.events").tag("status", "published").gauge().value());
        Assertions.assertEquals(6.0, meterRegistry.get("onlineorder.outbox.backlog").gauge().value());
        Assertions.assertEquals(3.0, meterRegistry.get("onlineorder.outbox.failed").gauge().value());
        Assertions.assertEquals(5.0, meterRegistry.get("onlineorder.dead_letter.total").gauge().value());
        Assertions.assertEquals(2.0, meterRegistry.get("onlineorder.dead_letter.events").tag("replay_status", "pending").gauge().value());
        Assertions.assertEquals(1.0, meterRegistry.get("onlineorder.dead_letter.events").tag("replay_status", "replay_failed").gauge().value());
        Assertions.assertEquals(2.0, meterRegistry.get("onlineorder.dead_letter.events").tag("replay_status", "replayed").gauge().value());
    }
}
