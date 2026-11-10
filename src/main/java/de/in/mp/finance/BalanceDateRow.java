package de.in.mp.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * One row of the balance matrix: all account snapshots of a reference date.
 */
public record BalanceDateRow(LocalDate date, Map<UUID, AccountSnapshot> snapshotsByAccount) {

    public AccountSnapshot snapshotFor(UUID accountId) {
        return snapshotsByAccount.get(accountId);
    }

    /**
     * Sum of all snapshot balances in this row (net position at the date).
     */
    public BigDecimal total() {
        return snapshotsByAccount.values().stream()
                .map(AccountSnapshot::getBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
