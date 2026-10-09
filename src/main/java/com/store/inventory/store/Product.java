package com.store.inventory.store;

import com.store.inventory.api.ProductCategory;

/**
 * Warehouse record for a single SKU. Not thread-safe: always accessed under the service lock.
 */
public final class Product {

    private final String sku;
    private ProductCategory category;
    private int stock;
    private boolean lowStockAlerted;

    public Product(String sku, ProductCategory category) {
        this.sku = sku;
        this.category = category;
    }

    public String sku() {
        return sku;
    }

    public ProductCategory category() {
        return category;
    }

    public int stock() {
        return stock;
    }

    public void changeCategory(ProductCategory category) {
        this.category = category;
    }

    /**
     * Restocking re-arms the low-stock alert: purchasing must hear about the product again
     * if it runs low after being replenished.
     */
    public void restock(int quantity) {
        if (quantity > Integer.MAX_VALUE - stock) {
            throw new IllegalArgumentException("Stock of " + sku + " cannot exceed " + Integer.MAX_VALUE + " units");
        }
        stock += quantity;
        lowStockAlerted = false;
    }

    public void sell(int quantity) {
        stock -= quantity;
    }

    public boolean isLowStockAlerted() {
        return lowStockAlerted;
    }

    public void markLowStockAlerted() {
        lowStockAlerted = true;
    }
}
