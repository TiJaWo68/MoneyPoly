package de.in.mp.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.in.mp.finance.AccountType;

/**
 * Tests the Scalable Capital statement parser with synthetic statement text
 * (fictional holder, address, IBAN and amounts) mirroring the PDF layout.
 */
class ScalableStatementParserTest {

    private static final String MONTHLY_STATEMENT = """
            Scalable Capital Bank GmbH • Seitzstraße 8e, 80538 München
            Musterweg 1
            12345 Musterstadt
            Deutschland
            Kontoauszug
            Zeitraum 01.10.2026 - 31.10.2026
            Auszug Nr. 1000000001
            Verrechnungskonto DE02120700700000000001 (DEUTDEFFXXX)
            Kontostand am 01.10.2026 10.000,00 EUR
            Kontostand am 31.10.2026 12.119,45 EUR
            Buchung Wertstellung Beschreibung Betrag
            01.10.2026 30.09.2026 Erhaltene Zinsen +150,00 EUR
            01.10.2026 30.09.2026 Abgezogener oder erstatteter Solidaritätszuschlag auf Kundenebene -1,55 EUR
            01.10.2026 30.09.2026 Auf Kundenebene einbehaltene oder erstattete Kapitalertragssteuer -29,00 EUR
            04.10.2026 04.10.2026 Überweisung +2.000,00 EUR
            Datum 02.11.2026
            Seite 1 / 2
            Max Mustermann
            Scalable Capital Bank GmbH • Seitzstraße 8e, 80538 München, Deutschland
            Verteilung Ihrer Einlagen
            zum Wertstellungsdatum 31.10.2026
            Absolut
            JPM EUR Liquidity LVNAV Select (dist.) 12.119,45 EUR
            Kontostand 12.119,45 EUR
            """;

    private static final String STATEMENT_WITHOUT_BOOKINGS = """
            Scalable Capital Bank GmbH • Seitzstraße 8e, 80538 München
            Max Mustermann
            Musterweg 1
            12345 Musterstadt
            Kontoauszug
            Zeitraum 01.11.2026 - 30.11.2026
            Verrechnungskonto DE02120700700000000001 (DEUTDEFFXXX)
            Kontostand am 01.11.2026 5.000,00 EUR
            Kontostand am 30.11.2026 5.000,00 EUR
            """;

    private final ScalableStatementParser parser = new ScalableStatementParser();

    @Test
    void supportsScalableStatementsOnly() {
        assertThat(parser.supports(MONTHLY_STATEMENT)).isTrue();
        assertThat(parser.supports("C24 Bank GmbH Kontoauszug 09/2026")).isFalse();
        assertThat(parser.supports(null)).isFalse();
    }

    @Test
    void extractsIbanClosingBalanceHolderAndBookingFlows() {
        List<StatementEntry> entries = parser.parse(MONTHLY_STATEMENT);

        assertThat(entries).hasSize(1);
        StatementEntry entry = entries.get(0);
        assertThat(entry.bank()).isEqualTo("Scalable Capital");
        assertThat(entry.accountLabel()).isEqualTo("Verrechnungskonto");
        assertThat(entry.suggestedType()).isEqualTo(AccountType.SAVINGS);
        assertThat(entry.iban()).isEqualTo("DE02120700700000000001");
        assertThat(entry.importKey()).isEqualTo("IBAN|DE02120700700000000001");
        assertThat(entry.closingDate()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(entry.closingBalance()).isEqualByComparingTo("12119.45");
        assertThat(entry.inflow()).isEqualByComparingTo("2150.00");
        assertThat(entry.outflow()).isEqualByComparingTo("30.55");
        assertThat(entry.accountHolder()).isEqualTo("Max Mustermann");
    }

    @Test
    void handlesStatementWithoutTransactions() {
        List<StatementEntry> entries = parser.parse(STATEMENT_WITHOUT_BOOKINGS);

        assertThat(entries).hasSize(1);
        StatementEntry entry = entries.get(0);
        assertThat(entry.closingDate()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(entry.closingBalance()).isEqualByComparingTo("5000.00");
        assertThat(entry.inflow()).isEqualByComparingTo("0.00");
        assertThat(entry.outflow()).isEqualByComparingTo("0.00");
        assertThat(entry.accountHolder()).isEqualTo("Max Mustermann");
    }

    @Test
    void rejectsMalformedScalableTextWithoutPeriod() {
        assertThatThrownBy(() -> parser.parse("Scalable Capital Bank GmbH Kontoauszug"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
