package de.in.mp.importer;

import java.math.BigDecimal;
import java.time.LocalDate;

import de.in.mp.finance.AccountType;

/**
 * Closing figures of one account extracted from a bank statement.
 *
 * @param bank           short bank identifier (e.g. {@code C24}, {@code Targobank})
 * @param accountLabel   product name as printed on the statement
 * @param suggestedType  account type derived from the product name
 * @param iban           IBAN without spaces, {@code null} if not printed
 * @param accountNumber  bank-internal account number, {@code null} if unknown
 * @param accountHolder  account holder as printed on the statement
 * @param closingDate    last day of the statement period
 * @param closingBalance balance at {@code closingDate}
 * @param inflow         total credits of the period (positive), may be {@code null}
 * @param outflow        total debits of the period (positive), may be {@code null}
 * @param importKey      stable identifier used to remember a manual account assignment
 */
public record StatementEntry(
        String bank,
        String accountLabel,
        AccountType suggestedType,
        String iban,
        String accountNumber,
        String accountHolder,
        LocalDate closingDate,
        BigDecimal closingBalance,
        BigDecimal inflow,
        BigDecimal outflow,
        String importKey) {
}
