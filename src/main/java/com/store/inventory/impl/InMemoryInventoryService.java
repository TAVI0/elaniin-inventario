package com.store.inventory.impl;

import static java.util.Objects.requireNonNull;

import com.store.inventory.alert.LowStockAlert;
import com.store.inventory.alert.LowStockAlerter;
import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.api.StockAlertListener;
import com.store.inventory.policy.CategoryPolicies;
import com.store.inventory.policy.CategoryPolicy;
import com.store.inventory.store.InMemoryInventoryStore;
import com.store.inventory.store.Product;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Orchestrates the store, category policies and low-stock alerting under a single lock:
 * every operation reads and writes stock and reservations together, so it must be atomic as a whole.
 */
public final class InMemoryInventoryService implements InventoryService {

    private final Clock clock;
    private final CategoryPolicies policies;
    private final InMemoryInventoryStore store = new InMemoryInventoryStore();
    private final LowStockAlerter alerter;
    private final ReentrantLock lock = new ReentrantLock();

    public InMemoryInventoryService(Clock clock, StockAlertListener alertListener, CategoryPolicies policies) {
        this.clock = requireNonNull(clock, "clock");
        this.alerter = new LowStockAlerter(requireNonNull(alertListener, "alertListener"));
        this.policies = requireNonNull(policies, "policies");
    }

    /**
     * Registering an existing SKU only changes its category: stock and reservations already
     * placed are kept, and only new reservations follow the new category rules.
     */
    @Override
    public void registerProduct(String sku, ProductCategory category) {
        requireNonNull(sku, "sku");
        requireNonNull(category, "category");
        lock.lock();
        try {
            store.findProduct(sku).ifPresentOrElse(
                    product -> product.changeCategory(category),
                    () -> store.addProduct(new Product(sku, category)));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void addStock(String sku, int quantity) {
        requireNonNull(sku, "sku");
        requirePositive(quantity);
        lock.lock();
        try {
            Product product = store.findProduct(sku)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown product: " + sku));
            product.restock(quantity);
        } finally {
            lock.unlock();
        }
    }

    /**
     * The app retries orders on slow connections, so reserving an order that already has an
     * active or confirmed reservation returns that reservation instead of reserving again.
     */
    @Override
    public Reservation reserve(String orderId, String sku, int quantity) {
        requireNonNull(orderId, "orderId");
        requireNonNull(sku, "sku");
        requirePositive(quantity);

        Reservation reservation;
        Optional<LowStockAlert> alert;
        lock.lock();
        try {
            Instant now = clock.instant();
            store.evictExpired(now);

            Optional<Reservation> previous = store.findActiveReservation(orderId)
                    .or(() -> store.findConfirmedReservation(orderId));
            if (previous.isPresent()) {
                return replay(previous.get(), sku, quantity);
            }

            Product product = store.findProduct(sku)
                    .orElseThrow(() -> new InsufficientStockException(sku, quantity, 0));
            CategoryPolicy policy = policies.policyFor(product.category());
            if (policy.exceedsOrderLimit(quantity)) {
                throw new OrderLimitExceededException(sku, quantity, policy.maxUnitsPerOrder().getAsInt());
            }
            int available = availableUnits(product);
            if (quantity > available) {
                throw new InsufficientStockException(sku, quantity, available);
            }

            reservation = new Reservation(orderId, sku, quantity, now.plus(policy.timeToPay()));
            store.addActiveReservation(reservation);
            alert = alerter.evaluate(product, available - quantity);
        } finally {
            lock.unlock();
        }
        alert.ifPresent(alerter::publish);
        return reservation;
    }

    @Override
    public void confirm(String orderId) {
        requireNonNull(orderId, "orderId");
        lock.lock();
        try {
            store.evictExpired(clock.instant());
            Reservation reservation = store.findActiveReservation(orderId)
                    .orElseThrow(() -> new IllegalStateException("No active reservation for order " + orderId));
            store.findProduct(reservation.sku()).orElseThrow().sell(reservation.quantity());
            store.markConfirmed(reservation);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int available(String sku) {
        requireNonNull(sku, "sku");
        lock.lock();
        try {
            store.evictExpired(clock.instant());
            return store.findProduct(sku).map(this::availableUnits).orElse(0);
        } finally {
            lock.unlock();
        }
    }

    private int availableUnits(Product product) {
        return product.stock() - store.reservedUnits(product.sku());
    }

    private static Reservation replay(Reservation previous, String sku, int quantity) {
        if (!previous.sku().equals(sku) || previous.quantity() != quantity) {
            throw new IllegalStateException("Order " + previous.orderId() + " already reserved "
                    + previous.quantity() + " units of " + previous.sku());
        }
        return previous;
    }

    private static void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive, was " + quantity);
        }
    }
}
