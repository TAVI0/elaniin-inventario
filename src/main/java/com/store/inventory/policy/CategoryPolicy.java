package com.store.inventory.policy;

import java.time.Duration;
import java.util.OptionalInt;

/**
 * Reservation rules that vary by product category.
 */
public interface CategoryPolicy {

    /**
     * How long a reservation holds its units while the customer pays.
     */
    Duration timeToPay();

    /**
     * Maximum units allowed on a single order, or empty when there is no limit.
     */
    OptionalInt maxUnitsPerOrder();

    default boolean exceedsOrderLimit(int quantity) {
        return maxUnitsPerOrder().isPresent() && quantity > maxUnitsPerOrder().getAsInt();
    }
}
