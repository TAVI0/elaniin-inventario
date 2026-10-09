package com.store.inventory.alert;

import com.store.inventory.api.StockAlertListener;
import com.store.inventory.store.Product;
import java.lang.System.Logger.Level;
import java.util.Optional;

/**
 * Decides when purchasing must be warned about low stock and delivers the warning.
 *
 * <p>Deciding and delivering are split on purpose: {@link #evaluate} mutates product state and
 * runs under the inventory lock, while {@link #publish} calls the listener, which may do slow
 * I/O (e-mail today, more channels later), and must run outside it.
 */
public final class LowStockAlerter {

    public static final int LOW_STOCK_THRESHOLD = 5;

    private static final System.Logger LOG = System.getLogger(LowStockAlerter.class.getName());

    private final StockAlertListener listener;

    public LowStockAlerter(StockAlertListener listener) {
        this.listener = listener;
    }

    /**
     * Alerts once per product until it is restocked (see {@link Product#restock}).
     */
    public Optional<LowStockAlert> evaluate(Product product, int availableUnits) {
        if (availableUnits > LOW_STOCK_THRESHOLD || product.isLowStockAlerted()) {
            return Optional.empty();
        }
        product.markLowStockAlerted();
        return Optional.of(new LowStockAlert(product.sku(), availableUnits));
    }

    /**
     * A failing alert channel must never fail the customer's reservation, which is already stored.
     */
    public void publish(LowStockAlert alert) {
        try {
            listener.onLowStock(alert.sku(), alert.availableUnits());
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "Low-stock alert could not be delivered for " + alert.sku(), e);
        }
    }
}
