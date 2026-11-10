package de.in.mp.importer;

import de.in.mp.finance.Account;

/**
 * A parsed statement entry together with the account it resolves to.
 *
 * @param fileName source file of the statement
 * @param entry    extracted closing figures
 * @param account  resolved target account, {@code null} if the user has to choose
 * @param source   how the account was resolved
 */
public record StatementMatch(String fileName, StatementEntry entry, Account account, MatchSource source) {

    public boolean resolved() {
        return account != null;
    }

    /**
     * How a statement entry was matched to an account.
     */
    public enum MatchSource {
        /** Assignment confirmed by the user during an earlier import. */
        IMPORT_KEY,
        /** IBAN or bank account number matches the account master data. */
        ACCOUNT_NUMBER,
        /** Unique account of the same bank, holder and type (no IBAN on statement). */
        HOLDER,
        /** No match; the user selects the account. */
        NONE
    }
}
