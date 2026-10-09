package com.store.inventory.policy;

import static com.store.inventory.policy.SimpleCategoryPolicy.limitedTo;
import static com.store.inventory.policy.SimpleCategoryPolicy.unlimited;
import static java.time.Duration.ofHours;
import static java.time.Duration.ofMinutes;
import static java.util.Objects.requireNonNull;

import com.store.inventory.api.ProductCategory;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/**
 * Reservation rules for every category. The table is complete or it cannot be built, so a
 * category without rules fails when the service is created, not on the first customer that
 * reserves it.
 */
public final class CategoryPolicies {

    private final Map<ProductCategory, CategoryPolicy> policies;

    public CategoryPolicies(Map<ProductCategory, ? extends CategoryPolicy> policies) {
        EnumSet<ProductCategory> missing = EnumSet.allOf(ProductCategory.class);
        missing.removeAll(policies.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("No reservation policy configured for categories " + missing);
        }
        this.policies = Collections.unmodifiableMap(new EnumMap<ProductCategory, CategoryPolicy>(policies));
    }

    /**
     * The rules agreed with the business. A new category is one more entry here.
     */
    public static CategoryPolicies standard() {
        return new CategoryPolicies(Map.of(
                ProductCategory.STANDARD, unlimited(ofMinutes(15)),
                ProductCategory.PRE_ORDER, unlimited(ofHours(24)),
                ProductCategory.FLASH_SALE, limitedTo(2, ofMinutes(5))));
    }

    public CategoryPolicy policyFor(ProductCategory category) {
        return policies.get(requireNonNull(category, "category"));
    }
}
