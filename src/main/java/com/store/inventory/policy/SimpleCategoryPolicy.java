package com.store.inventory.policy;

import java.time.Duration;
import java.util.OptionalInt;

public record SimpleCategoryPolicy(Duration timeToPay, OptionalInt maxUnitsPerOrder) implements CategoryPolicy {

    public SimpleCategoryPolicy {
        if (timeToPay.isNegative() || timeToPay.isZero()) {
            throw new IllegalArgumentException("Time to pay must be positive, was " + timeToPay);
        }
        if (maxUnitsPerOrder.isPresent() && maxUnitsPerOrder.getAsInt() <= 0) {
            throw new IllegalArgumentException("Order limit must be positive, was " + maxUnitsPerOrder.getAsInt());
        }
    }

    public static SimpleCategoryPolicy unlimited(Duration timeToPay) {
        return new SimpleCategoryPolicy(timeToPay, OptionalInt.empty());
    }

    public static SimpleCategoryPolicy limitedTo(int maxUnitsPerOrder, Duration timeToPay) {
        return new SimpleCategoryPolicy(timeToPay, OptionalInt.of(maxUnitsPerOrder));
    }
}
