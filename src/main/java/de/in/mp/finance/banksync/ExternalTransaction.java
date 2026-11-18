package de.in.mp.finance.banksync;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Normalized transaction item retrieved transiently during Open-Banking sync.
 */
public record ExternalTransaction(
        String externalTransactionId,
        LocalDate bookingDate,
        BigDecimal amount,
        String counterpartyName,
        String counterpartyIban,
        String purpose) {
}
