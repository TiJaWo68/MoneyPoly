package de.in.mp.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.in.mp.finance.AccountType;

/**
 * Tests the C24 statement parser with synthetic statement text (fictional
 * holder, address and IBAN) mirroring the layout of the PDF text extraction.
 */
class C24StatementParserTest {

    private static final String CHECKING_STATEMENT = """
            Max Mustermann
            Musterweg 1
            12345 Musterstadt
            C24 Smartkonto
            Girokonto
            IBAN: DE02120300000000202051
            BIC: DEFFDEFFXXX
            Kontoauszug 09/2026
            01.09.2026 - 30.09.2026
            Kontostand 1.234,56 €
            Transaktionsübersicht
            BuchungValutaTransaktionsinformationBetrag
            30.09.30.09.Zinsen
            +9,13 €
            Zusammenfassung
            Startsaldo1.000,00 €
            Kontobelastungen-765,44 €
            Kontogutschriften+1.000,00 €
            Endsaldo1.234,56 €
            Rechnungsabschluss 3/2026
            Zusammenfassung
            Startsaldo500,00 €
            Kontobelastungen-9.999,99 €
            Kontogutschriften+9.999,99 €
            Endsaldo1.234,56 €
            C24 Bank GmbH
            """;

    private static final String POCKET_STATEMENT = """
            Erika Mustermann
            Musterweg 1
            12345 Musterstadt
            Tagesgeld
            Tagesgeldpocket
            \s
            Kontoauszug 10/2026
            01.10.2026 - 31.10.2026
            Kontostand 0,00 €
            Zusammenfassung
            Startsaldo0,00 €
            Kontobelastungen-0,00 €
            Kontogutschriften+0,00 €
            Endsaldo0,00 €
            C24 Bank GmbH
            """;

    private final C24StatementParser parser = new C24StatementParser();

    @Test
    void supportsOnlyC24Statements() {
        assertThat(parser.supports(CHECKING_STATEMENT)).isTrue();
        assertThat(parser.supports("Some other bank statement")).isFalse();
    }

    @Test
    void parsesCheckingStatementWithMonthlySummaryInsteadOfQuarterlySummary() {
        List<StatementEntry> entries = parser.parse(CHECKING_STATEMENT);

        assertThat(entries).hasSize(1);
        StatementEntry entry = entries.get(0);
        assertThat(entry.bank()).isEqualTo("C24");
        assertThat(entry.accountLabel()).isEqualTo("C24 Smartkonto Girokonto");
        assertThat(entry.suggestedType()).isEqualTo(AccountType.CHECKING);
        assertThat(entry.iban()).isEqualTo("DE02120300000000202051");
        assertThat(entry.accountHolder()).isEqualTo("Max Mustermann");
        assertThat(entry.closingDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(entry.closingBalance()).isEqualByComparingTo("1234.56");
        assertThat(entry.inflow()).isEqualByComparingTo("1000.00");
        assertThat(entry.outflow()).isEqualByComparingTo("765.44");
        assertThat(entry.importKey()).isEqualTo("IBAN|DE02120300000000202051");
    }

    @Test
    void parsesPocketWithoutIbanUsingHolderBasedImportKey() {
        StatementEntry entry = parser.parse(POCKET_STATEMENT).get(0);

        assertThat(entry.iban()).isNull();
        assertThat(entry.accountLabel()).isEqualTo("Tagesgeld Tagesgeldpocket");
        assertThat(entry.suggestedType()).isEqualTo(AccountType.SAVINGS);
        assertThat(entry.accountHolder()).isEqualTo("Erika Mustermann");
        assertThat(entry.closingBalance()).isEqualByComparingTo("0");
        assertThat(entry.importKey()).isEqualTo("C24|erika mustermann|tagesgeld tagesgeldpocket");
    }

    @Test
    void parsesNegativeBalance() {
        String overdrawn = CHECKING_STATEMENT.replace("Kontostand 1.234,56 €", "Kontostand -42,10 €");

        assertThat(parser.parse(overdrawn).get(0).closingBalance()).isEqualByComparingTo("-42.10");
    }

    @Test
    void rejectsStatementWithoutPeriod() {
        assertThatThrownBy(() -> parser.parse("C24 Bank GmbH\nKontostand 1,00 €"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
