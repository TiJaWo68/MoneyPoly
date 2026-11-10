package de.in.mp.importer;

import java.util.List;

/**
 * Extracts closing balances from the plain text of a bank statement PDF.
 */
public interface BankStatementParser {

    /**
     * Whether this parser recognizes the statement layout.
     */
    boolean supports(String text);

    /**
     * All accounts contained in the statement. Never empty for supported text.
     *
     * @throws IllegalArgumentException if mandatory figures cannot be found
     */
    List<StatementEntry> parse(String text);
}
