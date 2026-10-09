package com.store.inventory.alert;

public record LowStockAlert(String sku, int availableUnits) {
}
