package com.laioffer.onlineorder;


import com.laioffer.onlineorder.entity.IdempotencyRequestEntity;
import com.laioffer.onlineorder.entity.OrderItemEntity;
import com.laioffer.onlineorder.repository.IdempotencyRequestRepository;
import com.laioffer.onlineorder.repository.OrderItemRepository;
import com.laioffer.onlineorder.service.H2CartItemQuantityUpdater;
import com.laioffer.onlineorder.service.H2IdempotencyRequestInitializer;
import com.laioffer.onlineorder.service.PostgresCartItemQuantityUpdater;
import com.laioffer.onlineorder.service.PostgresIdempotencyRequestInitializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;


import java.time.LocalDateTime;


@ExtendWith(MockitoExtension.class)
class ProfileSpecificPersistenceTests {

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private IdempotencyRequestRepository idempotencyRequestRepository;


    @Test
    void postgresCartItemQuantityUpdater_shouldDelegateToAtomicRepositoryUpsert() {
        PostgresCartItemQuantityUpdater updater = new PostgresCartItemQuantityUpdater(orderItemRepository);

        updater.incrementQuantity(1L, 2L, 4.89);

        Mockito.verify(orderItemRepository).incrementQuantity(1L, 2L, 4.89);
    }


    @Test
    void h2CartItemQuantityUpdater_shouldInsertNewItemWithoutPostgresUpsertSql() {
        H2CartItemQuantityUpdater updater = new H2CartItemQuantityUpdater(orderItemRepository);

        updater.incrementQuantity(1L, 2L, 4.89);

        ArgumentCaptor<OrderItemEntity> itemCaptor = ArgumentCaptor.forClass(OrderItemEntity.class);
        Mockito.verify(orderItemRepository).save(itemCaptor.capture());
        OrderItemEntity savedItem = itemCaptor.getValue();
        Assertions.assertNull(savedItem.id());
        Assertions.assertEquals(1L, savedItem.cartId());
        Assertions.assertEquals(2L, savedItem.menuItemId());
        Assertions.assertEquals(4.89, savedItem.price());
        Assertions.assertEquals(1, savedItem.quantity());
        Mockito.verify(orderItemRepository, Mockito.never()).incrementQuantity(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyDouble());
    }


    @Test
    void h2CartItemQuantityUpdater_shouldUpdateExistingItemWithoutPostgresUpsertSql() {
        H2CartItemQuantityUpdater updater = new H2CartItemQuantityUpdater(orderItemRepository);
        Mockito.when(orderItemRepository.findByCartIdAndMenuItemId(1L, 2L))
                .thenReturn(new OrderItemEntity(3L, 2L, 1L, 3.50, 2));

        updater.incrementQuantity(1L, 2L, 4.89);

        ArgumentCaptor<OrderItemEntity> itemCaptor = ArgumentCaptor.forClass(OrderItemEntity.class);
        Mockito.verify(orderItemRepository).save(itemCaptor.capture());
        OrderItemEntity savedItem = itemCaptor.getValue();
        Assertions.assertEquals(3L, savedItem.id());
        Assertions.assertEquals(1L, savedItem.cartId());
        Assertions.assertEquals(2L, savedItem.menuItemId());
        Assertions.assertEquals(4.89, savedItem.price());
        Assertions.assertEquals(3, savedItem.quantity());
        Mockito.verify(orderItemRepository, Mockito.never()).incrementQuantity(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyDouble());
    }


    @Test
    void postgresIdempotencyRequestInitializer_shouldDelegateToRepositoryUpsert() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 3, 12, 0);
        Mockito.when(idempotencyRequestRepository.insertIfAbsent(1L, "checkout:paid", "key", "hash", "PROCESSING", now, now))
                .thenReturn(1);
        PostgresIdempotencyRequestInitializer initializer = new PostgresIdempotencyRequestInitializer(idempotencyRequestRepository);

        int inserted = initializer.insertIfAbsent(1L, "checkout:paid", "key", "hash", "PROCESSING", now, now);

        Assertions.assertEquals(1, inserted);
        Mockito.verify(idempotencyRequestRepository).insertIfAbsent(1L, "checkout:paid", "key", "hash", "PROCESSING", now, now);
    }


    @Test
    void h2IdempotencyRequestInitializer_shouldInsertWhenRequestDoesNotExist() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 3, 12, 0);
        H2IdempotencyRequestInitializer initializer = new H2IdempotencyRequestInitializer(idempotencyRequestRepository);

        int inserted = initializer.insertIfAbsent(1L, "checkout:paid", "key", "hash", "PROCESSING", now, now);

        Assertions.assertEquals(1, inserted);
        ArgumentCaptor<IdempotencyRequestEntity> requestCaptor = ArgumentCaptor.forClass(IdempotencyRequestEntity.class);
        Mockito.verify(idempotencyRequestRepository).save(requestCaptor.capture());
        IdempotencyRequestEntity savedRequest = requestCaptor.getValue();
        Assertions.assertNull(savedRequest.id());
        Assertions.assertEquals(1L, savedRequest.customerId());
        Assertions.assertEquals("checkout:paid", savedRequest.scope());
        Assertions.assertEquals("key", savedRequest.idempotencyKey());
        Assertions.assertEquals("hash", savedRequest.requestHash());
        Assertions.assertEquals("PROCESSING", savedRequest.status());
        Assertions.assertNull(savedRequest.orderId());
        Assertions.assertEquals(now, savedRequest.createdAt());
        Assertions.assertEquals(now, savedRequest.updatedAt());
        Mockito.verify(idempotencyRequestRepository, Mockito.never()).insertIfAbsent(
                Mockito.anyLong(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.any(LocalDateTime.class),
                Mockito.any(LocalDateTime.class)
        );
    }


    @Test
    void h2IdempotencyRequestInitializer_shouldReturnZeroWhenRequestExists() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 3, 12, 0);
        Mockito.when(idempotencyRequestRepository.lockByCustomerIdAndScopeAndIdempotencyKey(1L, "checkout:paid", "key"))
                .thenReturn(new IdempotencyRequestEntity(
                        4L,
                        1L,
                        "checkout:paid",
                        "key",
                        "hash",
                        "PROCESSING",
                        null,
                        now,
                        now
                ));
        H2IdempotencyRequestInitializer initializer = new H2IdempotencyRequestInitializer(idempotencyRequestRepository);

        int inserted = initializer.insertIfAbsent(1L, "checkout:paid", "key", "hash", "PROCESSING", now, now);

        Assertions.assertEquals(0, inserted);
        Mockito.verify(idempotencyRequestRepository, Mockito.never()).save(Mockito.any());
        Mockito.verify(idempotencyRequestRepository, Mockito.never()).insertIfAbsent(
                Mockito.anyLong(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.any(LocalDateTime.class),
                Mockito.any(LocalDateTime.class)
        );
    }
}
