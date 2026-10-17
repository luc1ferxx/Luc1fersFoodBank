package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.entity.OrderStatusHistoryEntity;
import com.laioffer.onlineorder.fulfilment.order.OrderActor;
import com.laioffer.onlineorder.model.OrderStatus;
import com.laioffer.onlineorder.model.OrderStatusHistoryDto;
import com.laioffer.onlineorder.observability.TraceIds;
import com.laioffer.onlineorder.repository.OrderStatusHistoryRepository;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;


/**
 * The order module's audit trail: one order_status_history row per status change.
 *
 * <p>{@link #record} only runs inside the caller's transaction (propagation MANDATORY): the row commits or rolls back
 * with the guarded transition (or the checkout) that it describes. Callers write a row only for a change they made, so
 * same-status no-ops and idempotent retries add nothing.
 */
@Service
public class OrderStatusHistoryService {

    /**
     * How much earlier than the order's creation a status change may be stamped: changes are stamped with the clock of
     * the instance that made them, and instance clocks differ by milliseconds, not days.
     */
    static final Duration CLOCK_SKEW_MARGIN = Duration.ofDays(1);

    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final ObjectProvider<Tracer> tracer;


    public OrderStatusHistoryService(
            OrderStatusHistoryRepository orderStatusHistoryRepository,
            ObjectProvider<Tracer> tracer
    ) {
        this.orderStatusHistoryRepository = orderStatusHistoryRepository;
        this.tracer = tracer;
    }


    /**
     * @param fromStatus null when the order was just created
     * @param reason     the cancel reason of a cancellation, otherwise usually null
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(
            Long orderId,
            OrderStatus fromStatus,
            OrderStatus toStatus,
            OrderActor actor,
            String reason,
            LocalDateTime occurredAt
    ) {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(toStatus, "toStatus");
        Objects.requireNonNull(actor, "actor");
        orderStatusHistoryRepository.save(new OrderStatusHistoryEntity(
                null,
                orderId,
                fromStatus == null ? null : fromStatus.name(),
                toStatus.name(),
                actor.type().name(),
                actor.id(),
                reason,
                TraceIds.current(tracer.getIfAvailable()),
                occurredAt
        ));
    }


    /**
     * The order's status changes, oldest first. The caller checks that the order may be read.
     *
     * @param orderCreatedAt the order's creation time: on PostgreSQL order_status_history is partitioned by month
     *                       (V10), and only the partitions from that month on are read
     */
    public List<OrderStatusHistoryDto> getHistory(Long orderId, LocalDateTime orderCreatedAt) {
        Objects.requireNonNull(orderCreatedAt, "orderCreatedAt");
        return orderStatusHistoryRepository.findAllByOrderIdSince(orderId, orderCreatedAt.minus(CLOCK_SKEW_MARGIN))
                .stream()
                .map(OrderStatusHistoryDto::new)
                .toList();
    }
}
