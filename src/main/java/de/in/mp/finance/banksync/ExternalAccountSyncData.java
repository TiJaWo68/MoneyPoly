package de.in.mp.finance.banksync;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Balance and optional transaction payload fetched for a linked external account.
 */
public record ExternalAccountSyncData(
        String externalAccountId,
        LocalDate asOfDate,
        BigDecimal balance,
        BigDecimal monthlyInflow,
        BigDecimal monthlyOutflow,
        List<ExternalTransaction> transactions) {
}
