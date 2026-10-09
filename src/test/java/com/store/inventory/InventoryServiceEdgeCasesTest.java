package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InventoryServiceEdgeCasesTest {

    private static final Instant NOW = Instant.parse("2024-01-01T00:00:00Z");

    private MutableClock clock;
    private InventoryService service;

    @BeforeEach
    void setUp() {
        clock = MutableClock.startingAt(NOW);
        service = Inventory.create(clock, (sku, available) -> { });
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
    }

    @Test
    void reservationExpiresWhenTimeToPayIsOver() {
        service.addStock("SKU-1", 10);
        Reservation reservation = service.reserve("ORDER-1", "SKU-1", 4);
        assertEquals(NOW.plus(Duration.ofMinutes(15)), reservation.expiresAt());

        clock.advanceBy(Duration.ofMinutes(15).minusNanos(1));
        assertEquals(6, service.available("SKU-1"));

        clock.advanceBy(Duration.ofNanos(1));
        assertEquals(10, service.available("SKU-1"));
    }

    @Test
    void confirmingExpiredReservationThrows() {
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 4);

        clock.advanceBy(Duration.ofMinutes(15));

        assertThrows(IllegalStateException.class, () -> service.confirm("ORDER-1"));
    }

    @Test
    void confirmingUnknownOrderThrows() {
        assertThrows(IllegalStateException.class, () -> service.confirm("UNKNOWN-ORDER"));
    }

    @Test
    void confirmingTwiceThrows() {
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 4);
        service.confirm("ORDER-1");

        assertThrows(IllegalStateException.class, () -> service.confirm("ORDER-1"));
        assertEquals(6, service.available("SKU-1"));
    }

    @Test
    void retriedOrderGetsTheSameReservationWithoutReservingAgain() {
        service.addStock("SKU-1", 10);
        Reservation first = service.reserve("ORDER-1", "SKU-1", 4);

        clock.advanceBy(Duration.ofMinutes(1));
        Reservation retry = service.reserve("ORDER-1", "SKU-1", 4);

        assertEquals(first, retry);
        assertEquals(6, service.available("SKU-1"));
    }

    @Test
    void retryArrivingAfterConfirmationDoesNotReserveAgain() {
        service.addStock("SKU-1", 10);
        Reservation first = service.reserve("ORDER-1", "SKU-1", 4);
        service.confirm("ORDER-1");

        Reservation retry = service.reserve("ORDER-1", "SKU-1", 4);

        assertEquals(first, retry);
        assertEquals(6, service.available("SKU-1"));
    }

    @Test
    void retryIsAnsweredEvenIfStockRanOutInTheMeantime() {
        service.addStock("SKU-1", 5);
        Reservation first = service.reserve("ORDER-1", "SKU-1", 4);
        service.reserve("ORDER-2", "SKU-1", 1);

        assertEquals(first, service.reserve("ORDER-1", "SKU-1", 4));
    }

    @Test
    void reusingAnOrderIdForDifferentItemsIsRejected() {
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 4);

        assertThrows(IllegalStateException.class, () -> service.reserve("ORDER-1", "SKU-1", 2));
        assertEquals(6, service.available("SKU-1"));
    }

    @Test
    void orderCanReserveAgainAfterItsReservationExpired() {
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 4);

        clock.advanceBy(Duration.ofMinutes(15));
        Reservation renewed = service.reserve("ORDER-1", "SKU-1", 4);

        assertEquals(clock.instant().plus(Duration.ofMinutes(15)), renewed.expiresAt());
        assertEquals(6, service.available("SKU-1"));
    }

    @Test
    void reRegisteringProductChangesCategoryButKeepsStockAndReservations() {
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 4);

        service.registerProduct("SKU-1", ProductCategory.FLASH_SALE);

        assertEquals(6, service.available("SKU-1"));
        assertThrows(OrderLimitExceededException.class, () -> service.reserve("ORDER-2", "SKU-1", 3));
    }

    @Test
    void orderLimitIsCheckedBeforeStock() {
        service.registerProduct("SKU-FLASH", ProductCategory.FLASH_SALE);
        service.addStock("SKU-FLASH", 1);

        assertThrows(OrderLimitExceededException.class, () -> service.reserve("ORDER-1", "SKU-FLASH", 3));
    }

    @Test
    void availableForUnknownProductIsZero() {
        assertEquals(0, service.available("UNKNOWN-SKU"));
    }

    @Test
    void reservingUnknownProductThrowsInsufficientStock() {
        assertThrows(InsufficientStockException.class, () -> service.reserve("ORDER-1", "UNKNOWN-SKU", 1));
    }

    @Test
    void addingStockToUnknownProductThrows() {
        assertThrows(IllegalArgumentException.class, () -> service.addStock("UNKNOWN-SKU", 1));
    }

    @Test
    void reservingOrAddingStockWithNonPositiveQuantityThrows() {
        assertThrows(IllegalArgumentException.class, () -> service.addStock("SKU-1", 0));
        assertThrows(IllegalArgumentException.class, () -> service.addStock("SKU-1", -1));
        assertThrows(IllegalArgumentException.class, () -> service.reserve("ORDER-1", "SKU-1", 0));
        assertThrows(IllegalArgumentException.class, () -> service.reserve("ORDER-1", "SKU-1", -1));
    }

    @Test
    void stockThatWouldOverflowIsRejected() {
        service.addStock("SKU-1", Integer.MAX_VALUE);

        assertThrows(IllegalArgumentException.class, () -> service.addStock("SKU-1", 1));
        assertEquals(Integer.MAX_VALUE, service.available("SKU-1"));
    }

    @Test
    void concurrentCustomersNeverOversell() throws InterruptedException {
        int stock = 50;
        int customers = 100;
        service.addStock("SKU-1", stock);
        AtomicInteger successfulReservations = new AtomicInteger();

        runConcurrently(customers, customer -> {
            try {
                service.reserve("ORDER-" + customer, "SKU-1", 1);
                successfulReservations.incrementAndGet();
            } catch (InsufficientStockException expectedOnceSoldOut) {
            }
        });

        assertEquals(stock, successfulReservations.get());
        assertEquals(0, service.available("SKU-1"));
    }

    @Test
    void concurrentRetriesOfTheSameOrderReserveOnlyOnce() throws InterruptedException {
        service.addStock("SKU-1", 10);

        runConcurrently(20, attempt -> service.reserve("ORDER-1", "SKU-1", 3));

        assertEquals(7, service.available("SKU-1"));
    }

    private static void runConcurrently(int tasks, IntConsumer task) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(tasks);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(tasks);
        for (int i = 0; i < tasks; i++) {
            int taskNumber = i;
            executor.submit(() -> {
                try {
                    start.await();
                    task.accept(taskNumber);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "concurrent tasks did not finish in time");
        executor.shutdownNow();
    }
}
