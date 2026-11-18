package de.in.mp.finance.banksync;

import java.util.List;

/**
 * Aggregated outcome of syncing one or more Open-Banking linked accounts.
 */
public record BankSyncResult(
        int syncedAccounts,
        int skippedAccounts,
        int failedAccounts,
        List<String> errors) {
}
