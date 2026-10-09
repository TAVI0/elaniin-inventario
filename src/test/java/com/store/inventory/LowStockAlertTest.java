package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.store.inventory.alert.LowStockAlerter;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LowStockAlertTest {

    private record AlertCall(String sku, int availableUnits) {
    }

    private final List<AlertCall> alertCalls = new ArrayList<>();
    private MutableClock clock;
    private InventoryService service;

    @BeforeEach
    void setUp() {
        clock = MutableClock.startingAt(Instant.parse("2024-01-01T00:00:00Z"));
        service = Inventory.create(clock, (sku, available) -> alertCalls.add(new AlertCall(sku, available)));
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
    }

    @Test
    void noAlertWhileAboveTheThreshold() {
        service.addStock("SKU-1", 10);

        service.reserve("ORDER-1", "SKU-1", 4);

        assertTrue(alertCalls.isEmpty());
    }

    @Test
    void alertFiresWhenAvailableUnitsDropToTheThreshold() {
        service.addStock("SKU-1", 10);

        service.reserve("ORDER-1", "SKU-1", 5);

        assertEquals(List.of(new AlertCall("SKU-1", 5)), alertCalls);
    }

    @Test
    void alertDoesNotRepeatWhileTheProductIsNotRestocked() {
        service.addStock("SKU-1", 10);

        service.reserve("ORDER-1", "SKU-1", 5);
        service.reserve("ORDER-2", "SKU-1", 1);
        service.confirm("ORDER-2");

        assertEquals(1, alertCalls.size());
    }

    @Test
    void expiredReservationsDoNotCountAsARestock() {
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 5);

        clock.advanceBy(Duration.ofMinutes(15));
        service.reserve("ORDER-2", "SKU-1", 6);

        assertEquals(1, alertCalls.size());
    }

    @Test
    void alertFiresAgainAfterARestock() {
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 5);

        service.addStock("SKU-1", 10);
        service.reserve("ORDER-2", "SKU-1", 10);

        assertEquals(List.of(new AlertCall("SKU-1", 5), new AlertCall("SKU-1", 5)), alertCalls);
    }

    @Test
    void restockThatLeavesTheProductLowAlertsAgainOnTheNextReservation() {
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 8);

        service.addStock("SKU-1", 1);
        service.reserve("ORDER-2", "SKU-1", 1);

        assertEquals(List.of(new AlertCall("SKU-1", 2), new AlertCall("SKU-1", 2)), alertCalls);
    }

    @Test
    void failingAlertChannelDoesNotFailTheReservationAndIsLogged() {
        InventoryService withBrokenChannel = Inventory.create(clock, (sku, available) -> {
            throw new IllegalStateException("mail server down");
        });
        withBrokenChannel.registerProduct("SKU-1", ProductCategory.STANDARD);
        withBrokenChannel.addStock("SKU-1", 6);

        List<LogRecord> logged = captureAlerterLogs(() -> withBrokenChannel.reserve("ORDER-1", "SKU-1", 2));

        assertEquals(4, withBrokenChannel.available("SKU-1"));
        assertEquals(1, logged.size());
        assertEquals(Level.SEVERE, logged.get(0).getLevel());
        assertTrue(logged.get(0).getMessage().contains("SKU-1"));
        assertEquals("mail server down", logged.get(0).getThrown().getMessage());
    }

    /**
     * Captures the alerter's log records instead of printing them, so an expected failure
     * does not show up as a stack trace in the build output.
     */
    private static List<LogRecord> captureAlerterLogs(Runnable action) {
        Logger logger = Logger.getLogger(LowStockAlerter.class.getName());
        List<LogRecord> records = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                records.add(logRecord);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        boolean usedParentHandlers = logger.getUseParentHandlers();
        logger.addHandler(capture);
        logger.setUseParentHandlers(false);
        try {
            action.run();
        } finally {
            logger.removeHandler(capture);
            logger.setUseParentHandlers(usedParentHandlers);
        }
        return records;
    }
}
