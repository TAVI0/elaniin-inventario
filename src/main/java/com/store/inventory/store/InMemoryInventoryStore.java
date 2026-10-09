package com.store.inventory.store;

import com.store.inventory.api.Reservation;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Products and reservations held in memory. Not thread-safe: always accessed under the service lock.
 *
 * <p>Expired reservations are removed rather than flagged, so an expired reservation is
 * indistinguishable from one that never existed. Confirmed reservations are kept so a
 * retried order can be answered with its original reservation instead of reserving again.
 */
public final class InMemoryInventoryStore {

    private final Map<String, Product> products = new HashMap<>();
    private final Map<String, Reservation> activeReservations = new HashMap<>();
    private final Map<String, Reservation> confirmedReservations = new HashMap<>();

    public void addProduct(Product product) {
        products.put(product.sku(), product);
    }

    public Optional<Product> findProduct(String sku) {
        return Optional.ofNullable(products.get(sku));
    }

    public Optional<Reservation> findActiveReservation(String orderId) {
        return Optional.ofNullable(activeReservations.get(orderId));
    }

    public Optional<Reservation> findConfirmedReservation(String orderId) {
        return Optional.ofNullable(confirmedReservations.get(orderId));
    }

    public void addActiveReservation(Reservation reservation) {
        activeReservations.put(reservation.orderId(), reservation);
    }

    public void markConfirmed(Reservation reservation) {
        activeReservations.remove(reservation.orderId());
        confirmedReservations.put(reservation.orderId(), reservation);
    }

    /**
     * A reservation is expired from its {@code expiresAt} instant onwards: once the time to pay
     * is over, the units go back to stock.
     */
    public void evictExpired(Instant now) {
        activeReservations.values().removeIf(reservation -> !now.isBefore(reservation.expiresAt()));
    }

    public int reservedUnits(String sku) {
        return activeReservations.values().stream()
                .filter(reservation -> reservation.sku().equals(sku))
                .mapToInt(Reservation::quantity)
                .sum();
    }
}
