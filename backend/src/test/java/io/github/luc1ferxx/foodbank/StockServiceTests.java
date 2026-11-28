package io.github.luc1ferxx.foodbank;


import io.github.luc1ferxx.foodbank.audit.AuditActions;
import io.github.luc1ferxx.foodbank.audit.AuditActor;
import io.github.luc1ferxx.foodbank.audit.AuditLogService;
import io.github.luc1ferxx.foodbank.entity.MenuItemEntity;
import io.github.luc1ferxx.foodbank.entity.OrderHistoryItemEntity;
import io.github.luc1ferxx.foodbank.entity.OrderItemEntity;
import io.github.luc1ferxx.foodbank.exception.BadRequestException;
import io.github.luc1ferxx.foodbank.exception.ConflictException;
import io.github.luc1ferxx.foodbank.exception.ResourceNotFoundException;
import io.github.luc1ferxx.foodbank.model.MenuItemStockDto;
import io.github.luc1ferxx.foodbank.repository.MenuItemRepository;
import io.github.luc1ferxx.foodbank.repository.OrderHistoryItemRepository;
import io.github.luc1ferxx.foodbank.service.StockService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;


import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;


@ExtendWith(MockitoExtension.class)
class StockServiceTests {

    private static final long ORDER_ID = 40L;
    private static final long CART_ID = 7L;
    private static final AuditActor ADMIN = AuditActor.admin(9L);

    private static final MenuItemEntity BURGER = menuItem(3L, "Burger");
    private static final MenuItemEntity FRIES = menuItem(9L, "Fries");

    /**
     * Two burgers and one fries, the fries first: the cart's order is not the lock order.
     */
    private static final List<OrderItemEntity> CART = List.of(cartLine(9L, 1), cartLine(3L, 2));
    private static final Map<Long, MenuItemEntity> MENU_ITEMS = Map.of(3L, BURGER, 9L, FRIES);

    private static final String BURGER_OUT_OF_STOCK =
            "Not enough Burger left. Lower its quantity or remove it from your cart, then try again.";

    @Mock
    private MenuItemRepository menuItemRepository;

    @Mock
    private OrderHistoryItemRepository orderHistoryItemRepository;

    @Mock
    private AuditLogService auditLogService;

    private StockService stockService;


    @BeforeEach
    void setup() {
        stockService = new StockService(menuItemRepository, orderHistoryItemRepository, auditLogService);
    }


    @Test
    void requireInStock_whenNothingIsLimited_shouldReadNoStock() {
        Mockito.when(menuItemRepository.findStockLimitedIds(Set.of(3L, 9L))).thenReturn(List.of());

        stockService.requireInStock(CART, MENU_ITEMS);

        Mockito.verifyNoMoreInteractions(menuItemRepository);
        Mockito.verifyNoInteractions(orderHistoryItemRepository);
    }


    /**
     * Exactly enough burgers, and fries whose limit was removed after the first read: the checkout goes on, and the
     * check writes nothing.
     */
    @Test
    void requireInStock_whenEnoughIsLeft_shouldPassWithoutWriting() {
        Mockito.when(menuItemRepository.findStockLimitedIds(Set.of(3L, 9L))).thenReturn(List.of(3L, 9L));
        Mockito.when(menuItemRepository.findStockRemaining(3L)).thenReturn(2);
        Mockito.when(menuItemRepository.findStockRemaining(9L)).thenReturn(null);

        stockService.requireInStock(CART, MENU_ITEMS);

        Mockito.verify(menuItemRepository, Mockito.never()).takeStock(Mockito.any(), Mockito.anyInt());
        Mockito.verifyNoInteractions(orderHistoryItemRepository);
    }


    @Test
    void requireInStock_whenAnItemIsShort_shouldConflictWithItsNameAndNoNumber() {
        Mockito.when(menuItemRepository.findStockLimitedIds(Set.of(3L, 9L))).thenReturn(List.of(3L));
        Mockito.when(menuItemRepository.findStockRemaining(3L)).thenReturn(1);

        ConflictException conflict = Assertions.assertThrows(
                ConflictException.class,
                () -> stockService.requireInStock(CART, MENU_ITEMS)
        );

        Assertions.assertEquals(StockService.OUT_OF_STOCK, conflict.getCode());
        Assertions.assertEquals(BURGER_OUT_OF_STOCK, conflict.getMessage());
        Assertions.assertFalse(conflict.getMessage().matches(".*\\d.*"), conflict.getMessage());
        Mockito.verify(menuItemRepository, Mockito.never()).takeStock(Mockito.any(), Mockito.anyInt());
        Mockito.verifyNoInteractions(orderHistoryItemRepository);
    }


    @Test
    void reserve_shouldTakeInAscendingIdOrderAndMarkEachLine() {
        Mockito.when(menuItemRepository.findStockLimitedIds(Set.of(3L, 9L))).thenReturn(List.of(3L, 9L));
        Mockito.when(menuItemRepository.takeStock(3L, 2)).thenReturn(1);
        Mockito.when(menuItemRepository.takeStock(9L, 1)).thenReturn(1);
        Mockito.when(orderHistoryItemRepository.markStockReserved(ORDER_ID, 3L)).thenReturn(1);
        Mockito.when(orderHistoryItemRepository.markStockReserved(ORDER_ID, 9L)).thenReturn(1);

        stockService.reserve(ORDER_ID, CART, MENU_ITEMS);

        InOrder steps = Mockito.inOrder(menuItemRepository, orderHistoryItemRepository);
        steps.verify(menuItemRepository).takeStock(3L, 2);
        steps.verify(orderHistoryItemRepository).markStockReserved(ORDER_ID, 3L);
        steps.verify(menuItemRepository).takeStock(9L, 1);
        steps.verify(orderHistoryItemRepository).markStockReserved(ORDER_ID, 9L);
        Mockito.verify(menuItemRepository, Mockito.never()).findStockRemaining(Mockito.any());
    }


    @Test
    void reserve_whenTheLimitWasRemovedMeanwhile_shouldSkipTheItemWithoutMarking() {
        Mockito.when(menuItemRepository.findStockLimitedIds(Set.of(3L, 9L))).thenReturn(List.of(3L));
        Mockito.when(menuItemRepository.takeStock(3L, 2)).thenReturn(0);
        Mockito.when(menuItemRepository.findStockRemaining(3L)).thenReturn(null);

        stockService.reserve(ORDER_ID, CART, MENU_ITEMS);

        Mockito.verifyNoInteractions(orderHistoryItemRepository);
    }


    /**
     * The second item is short: the 409 rolls back the checkout's transaction, the burgers already taken included.
     */
    @Test
    void reserve_whenAnItemIsStillLimitedButShort_shouldConflict() {
        Mockito.when(menuItemRepository.findStockLimitedIds(Set.of(3L, 9L))).thenReturn(List.of(3L, 9L));
        Mockito.when(menuItemRepository.takeStock(3L, 2)).thenReturn(1);
        Mockito.when(orderHistoryItemRepository.markStockReserved(ORDER_ID, 3L)).thenReturn(1);
        Mockito.when(menuItemRepository.takeStock(9L, 1)).thenReturn(0);
        Mockito.when(menuItemRepository.findStockRemaining(9L)).thenReturn(0);

        ConflictException conflict = Assertions.assertThrows(
                ConflictException.class,
                () -> stockService.reserve(ORDER_ID, CART, MENU_ITEMS)
        );

        Assertions.assertEquals(StockService.OUT_OF_STOCK, conflict.getCode());
        Assertions.assertEquals(
                "Not enough Fries left. Lower its quantity or remove it from your cart, then try again.",
                conflict.getMessage()
        );
        Mockito.verify(orderHistoryItemRepository, Mockito.never()).markStockReserved(ORDER_ID, 9L);
    }


    @Test
    void release_shouldGiveBackInAscendingOrderAndClearTheMarks() {
        Mockito.when(orderHistoryItemRepository.findStockReservedByOrderId(ORDER_ID))
                .thenReturn(List.of(orderLine(3L, 2), orderLine(9L, 1)));

        stockService.release(ORDER_ID);

        InOrder steps = Mockito.inOrder(menuItemRepository, orderHistoryItemRepository);
        steps.verify(orderHistoryItemRepository).findStockReservedByOrderId(ORDER_ID);
        steps.verify(menuItemRepository).giveBackStock(3L, 2);
        steps.verify(menuItemRepository).giveBackStock(9L, 1);
        steps.verify(orderHistoryItemRepository).clearStockReserved(ORDER_ID);
        Mockito.verifyNoMoreInteractions(menuItemRepository, orderHistoryItemRepository);
    }


    @Test
    void release_whenTheOrderTookNoStock_shouldDoNothing() {
        Mockito.when(orderHistoryItemRepository.findStockReservedByOrderId(ORDER_ID)).thenReturn(List.of());

        stockService.release(ORDER_ID);

        Mockito.verifyNoInteractions(menuItemRepository);
        Mockito.verify(orderHistoryItemRepository, Mockito.never()).clearStockReserved(Mockito.any());
    }


    @Test
    void setStock_whenItChangesTheLimit_shouldAuditIt() {
        Mockito.when(menuItemRepository.findById(3L)).thenReturn(Optional.of(BURGER));
        Mockito.when(menuItemRepository.setStockRemaining(3L, 7)).thenReturn(1);

        Assertions.assertEquals(new MenuItemStockDto(3L, 7), stockService.setStock(3L, 7, ADMIN));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, ?>> details = ArgumentCaptor.forClass(Map.class);
        Mockito.verify(auditLogService).record(
                Mockito.eq(ADMIN),
                Mockito.eq(AuditActions.MENU_ITEM_STOCK_UPDATE),
                Mockito.eq(AuditActions.TARGET_MENU_ITEM),
                Mockito.eq(3L),
                details.capture()
        );
        Assertions.assertEquals(Map.of("restaurant_id", 2L, "limited", true, "remaining", 7), details.getValue());
    }


    @Test
    void setStock_toTheLimitTheItemHas_shouldNotAudit() {
        Mockito.when(menuItemRepository.findById(3L)).thenReturn(Optional.of(BURGER));
        Mockito.when(menuItemRepository.setStockRemaining(3L, 7)).thenReturn(0);

        Assertions.assertEquals(new MenuItemStockDto(3L, 7), stockService.setStock(3L, 7, ADMIN));

        Mockito.verifyNoInteractions(auditLogService);
    }


    @Test
    void setStock_ofAnUnknownItem_shouldBeNotFound() {
        Mockito.when(menuItemRepository.findById(3L)).thenReturn(Optional.empty());

        Assertions.assertThrows(ResourceNotFoundException.class, () -> stockService.setStock(3L, 7, ADMIN));

        Mockito.verifyNoMoreInteractions(menuItemRepository);
        Mockito.verifyNoInteractions(auditLogService);
    }


    @ParameterizedTest
    @ValueSource(ints = {-1, StockService.MAX_STOCK + 1})
    void setStock_outOfRange_shouldBeBadRequest(int remaining) {
        Mockito.when(menuItemRepository.findById(3L)).thenReturn(Optional.of(BURGER));

        Assertions.assertThrows(BadRequestException.class, () -> stockService.setStock(3L, remaining, ADMIN));

        Mockito.verifyNoMoreInteractions(menuItemRepository);
        Mockito.verifyNoInteractions(auditLogService);
    }


    @Test
    void removeStockLimit_whenTheItemHadOne_shouldAuditIt() {
        Mockito.when(menuItemRepository.findById(3L)).thenReturn(Optional.of(BURGER));
        Mockito.when(menuItemRepository.removeStockLimit(3L)).thenReturn(1);

        Assertions.assertEquals(new MenuItemStockDto(3L, null), stockService.removeStockLimit(3L, ADMIN));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, ?>> details = ArgumentCaptor.forClass(Map.class);
        Mockito.verify(auditLogService).record(
                Mockito.eq(ADMIN),
                Mockito.eq(AuditActions.MENU_ITEM_STOCK_UPDATE),
                Mockito.eq(AuditActions.TARGET_MENU_ITEM),
                Mockito.eq(3L),
                details.capture()
        );
        Assertions.assertEquals(2L, details.getValue().get("restaurant_id"));
        Assertions.assertEquals(false, details.getValue().get("limited"));
        // Null, which the audit log leaves out of the entry.
        Assertions.assertNull(details.getValue().get("remaining"));
    }


    @Test
    void removeStockLimit_whenTheItemHadNone_shouldNotAudit() {
        Mockito.when(menuItemRepository.findById(3L)).thenReturn(Optional.of(BURGER));
        Mockito.when(menuItemRepository.removeStockLimit(3L)).thenReturn(0);

        Assertions.assertEquals(new MenuItemStockDto(3L, null), stockService.removeStockLimit(3L, ADMIN));

        Mockito.verifyNoInteractions(auditLogService);
    }


    @Test
    void removeStockLimit_ofAnUnknownItem_shouldBeNotFound() {
        Mockito.when(menuItemRepository.findById(3L)).thenReturn(Optional.empty());

        Assertions.assertThrows(ResourceNotFoundException.class, () -> stockService.removeStockLimit(3L, ADMIN));

        Mockito.verifyNoMoreInteractions(menuItemRepository);
        Mockito.verifyNoInteractions(auditLogService);
    }


    @Test
    void getStock_shouldReturnTheLimitOrNoneAndNotFoundForAnUnknownItem() {
        Mockito.when(menuItemRepository.findById(3L)).thenReturn(Optional.of(BURGER));
        Mockito.when(menuItemRepository.findById(9L)).thenReturn(Optional.of(FRIES));
        Mockito.when(menuItemRepository.findById(5L)).thenReturn(Optional.empty());
        Mockito.when(menuItemRepository.findStockRemaining(3L)).thenReturn(7);
        Mockito.when(menuItemRepository.findStockRemaining(9L)).thenReturn(null);

        Assertions.assertEquals(new MenuItemStockDto(3L, 7), stockService.getStock(3L));
        Assertions.assertEquals(new MenuItemStockDto(9L, null), stockService.getStock(9L));
        Assertions.assertThrows(ResourceNotFoundException.class, () -> stockService.getStock(5L));
    }


    /**
     * An item of restaurant 2.
     */
    private static MenuItemEntity menuItem(long id, String name) {
        return new MenuItemEntity(id, 2L, name, "", new BigDecimal("4.00"), "", "USD");
    }


    private static OrderItemEntity cartLine(long menuItemId, int quantity) {
        return new OrderItemEntity(100L + menuItemId, menuItemId, CART_ID, new BigDecimal("4.00"), quantity, "USD");
    }


    private static OrderHistoryItemEntity orderLine(long menuItemId, int quantity) {
        return new OrderHistoryItemEntity(200L + menuItemId, ORDER_ID, menuItemId, 2L, new BigDecimal("4.00"), quantity,
                "Item " + menuItemId, "", "", "USD");
    }
}
