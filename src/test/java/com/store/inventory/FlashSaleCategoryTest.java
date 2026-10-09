package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FlashSaleCategoryTest {

    private MutableClock clock;
    private InventoryService service;

    @BeforeEach
    void setUp() {
        clock = MutableClock.startingAt(Instant.parse("2024-01-01T00:00:00Z"));
        service = Inventory.create(clock, (sku, available) -> { });
    }

    @Test
    void flashSaleRejectsOrdersAboveTheLimit() {
        service.registerProduct("SKU-FLASH", ProductCategory.FLASH_SALE);
        service.addStock("SKU-FLASH", 10);

        assertThrows(OrderLimitExceededException.class, () -> service.reserve("ORDER-1", "SKU-FLASH", 3));
    }

    @Test
    void flashSaleAllowsExactlyTheLimit() {
        service.registerProduct("SKU-FLASH", ProductCategory.FLASH_SALE);
        service.addStock("SKU-FLASH", 10);

        var reservation = service.reserve("ORDER-1", "SKU-FLASH", 2);
        assertEquals(2, reservation.quantity());
    }

    @Test
    void flashSaleReservationExpiresAfterFiveMinutes() {
        service.registerProduct("SKU-FLASH", ProductCategory.FLASH_SALE);
        service.addStock("SKU-FLASH", 10);
        service.reserve("ORDER-1", "SKU-FLASH", 2);

        clock.advanceBy(Duration.ofMinutes(4));
        assertEquals(8, service.available("SKU-FLASH"));

        clock.advanceBy(Duration.ofMinutes(2));
        assertEquals(10, service.available("SKU-FLASH"));
    }

    @Test
    void preOrderOutlivesStandardTtlButExpiresAfterTwentyFourHours() {
        service.registerProduct("SKU-PRE", ProductCategory.PRE_ORDER);
        service.addStock("SKU-PRE", 10);
        service.reserve("ORDER-1", "SKU-PRE", 2);

        clock.advanceBy(Duration.ofMinutes(15));
        assertEquals(8, service.available("SKU-PRE"));

        clock.advanceBy(Duration.ofHours(24));
        assertEquals(10, service.available("SKU-PRE"));
    }
}
