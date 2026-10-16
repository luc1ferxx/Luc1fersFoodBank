package com.laioffer.onlineorder.fulfilment.dispatch;


import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;


public interface DeliveryRepository extends ListCrudRepository<DeliveryEntity, Long> {

    /**
     * Records the assignment unless the order already has a delivery, and returns the rows inserted: 1 or 0.
     * Target-less ON CONFLICT so that H2 (PostgreSQL mode) parses it; the only constraint an insert can violate is
     * uq_delivery_order.
     */
    @Modifying
    @Query("""
            INSERT INTO deliveries (
                order_id,
                customer_id,
                restaurant_id,
                rider_id,
                status,
                assigned_at,
                updated_at
            ) VALUES (
                :orderId,
                :customerId,
                :restaurantId,
                :riderId,
                'ASSIGNED',
                :assignedAt,
                :assignedAt
            )
            ON CONFLICT DO NOTHING
            """)
    int insertAssignedIfAbsent(Long orderId, Long customerId, Long restaurantId, Long riderId, LocalDateTime assignedAt);


    @Query("SELECT * FROM deliveries WHERE order_id = :orderId")
    DeliveryEntity findByOrderId(Long orderId);


    @Query("SELECT * FROM deliveries WHERE id = :id FOR UPDATE")
    DeliveryEntity lockById(Long id);


    @Query("SELECT * FROM deliveries WHERE order_id = :orderId FOR UPDATE")
    DeliveryEntity lockByOrderId(Long orderId);


    /**
     * A delivery of this rider, locked; null for another rider's delivery as for one that does not exist.
     */
    @Query("SELECT * FROM deliveries WHERE id = :id AND rider_id = :riderId FOR UPDATE")
    DeliveryEntity lockByIdAndRiderId(Long id, Long riderId);


    /**
     * The rider's deliveries in one of {@code statuses}, newest first; served by idx_deliveries_rider_status.
     */
    @Query("""
            SELECT *
            FROM deliveries
            WHERE rider_id = :riderId
              AND status IN (:statuses)
            ORDER BY assigned_at DESC, id DESC
            LIMIT :limit
            """)
    List<DeliveryEntity> findByRiderIdAndStatuses(Long riderId, Collection<String> statuses, int limit);


    @Query("""
            SELECT *
            FROM deliveries
            ORDER BY assigned_at DESC, id DESC
            LIMIT :limit
            """)
    List<DeliveryEntity> findRecent(int limit);


    @Modifying
    @Query("""
            UPDATE deliveries
            SET status = :status,
                picked_up_at = :pickedUpAt,
                delivered_at = :deliveredAt,
                cancelled_at = :cancelledAt,
                updated_at = :updatedAt
            WHERE id = :id
            """)
    int updateStatus(
            Long id,
            String status,
            LocalDateTime pickedUpAt,
            LocalDateTime deliveredAt,
            LocalDateTime cancelledAt,
            LocalDateTime updatedAt
    );
}
