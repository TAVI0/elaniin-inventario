package com.store.inventory.policy;

import static com.store.inventory.policy.SimpleCategoryPolicy.unlimited;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.store.inventory.api.ProductCategory;
import java.time.Duration;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CategoryPoliciesTest {

    private final CategoryPolicies standard = CategoryPolicies.standard();

    @ParameterizedTest
    @EnumSource(ProductCategory.class)
    void everyCategoryHasAPolicy(ProductCategory category) {
        assertNotNull(standard.policyFor(category));
    }

    @Test
    void policiesMatchTheBusinessTable() {
        assertPolicy(ProductCategory.STANDARD, Duration.ofMinutes(15), OptionalInt.empty());
        assertPolicy(ProductCategory.PRE_ORDER, Duration.ofHours(24), OptionalInt.empty());
        assertPolicy(ProductCategory.FLASH_SALE, Duration.ofMinutes(5), OptionalInt.of(2));
    }

    @Test
    void tableMissingACategoryCannotBeBuilt() {
        Map<ProductCategory, CategoryPolicy> incomplete = Map.of(
                ProductCategory.STANDARD, unlimited(Duration.ofMinutes(15)));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new CategoryPolicies(incomplete));

        assertTrue(error.getMessage().contains("PRE_ORDER"));
        assertTrue(error.getMessage().contains("FLASH_SALE"));
    }

    private void assertPolicy(ProductCategory category, Duration timeToPay, OptionalInt limit) {
        CategoryPolicy policy = standard.policyFor(category);
        assertEquals(timeToPay, policy.timeToPay());
        assertEquals(limit, policy.maxUnitsPerOrder());
    }
}
