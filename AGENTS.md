# AGENTS.md

## Domain

Inventory reservation service for an online store (technical challenge, see `README.md`).
When a customer submits a cart, each product travels as an independent order and its units
are reserved while the customer pays. Unpaid reservations expire and release their units;
confirmed reservations become sales and never return to stock.

Rules per category (`policy/CategoryPolicies`):

| Category     | Time to pay | Per-order limit |
| ------------ | ----------- | --------------- |
| `STANDARD`   | 15 minutes  | none            |
| `PRE_ORDER`  | 24 hours    | none            |
| `FLASH_SALE` | 5 minutes   | 2 units         |

Key behaviors:

- `reserve` is idempotent per `orderId`: the mobile app retries orders on slow connections.
- Expiry is lazy: expired reservations are evicted at the start of `reserve`, `confirm` and `available`, using the injected `Clock`. There is no background thread.
- Purchasing gets one low-stock alert (available units <= 5) per product until it is restocked with `addStock`.
- Alerts are sent outside the lock and a failing channel never fails a reservation.

## Do not touch

- `src/main/java/com/store/inventory/api/`: fixed contract used by the grading tests.
- The signature of `Inventory.create(Clock, StockAlertListener)`.
- `src/test/java/com/store/inventory/InventoryServiceTest.java`: the original tests must pass unmodified.

## Package structure

- `com.store.inventory`: `Inventory`, the factory entry point.
- `impl`: `InMemoryInventoryService`, orchestrates everything under a single lock.
- `policy`: category rules as a data table. A new category is one entry there.
- `store`: in-memory products and reservations, lazy expiry.
- `alert`: low-stock decision (under the lock) and delivery (outside it).

## Running tests

Requires Java 21+ and Maven.

```
mvn test
```

Time-dependent tests use `MutableClock` (test helper) instead of sleeping. Keep it that way:
never call `Instant.now()` in production code, always read time from the injected `Clock`.

## Decisions

`DECISIONS.md` (in Spanish, for the reviewing team) holds the assumptions, design rationale,
what was left out and what changes before production. Keep it in sync when behavior changes.
