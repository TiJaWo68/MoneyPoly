package de.in.mp.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.in.mp.finance.AccountType;

/**
 * Tests {@link TradeRepublicCashStatementParser} with synthetic Trade Republic
 * cash account statements (fictional holder, address and IBAN).
 */
class TradeRepublicCashStatementParserTest {

    private static final String MULTI_YEAR_STATEMENT = """
            TRADE REPUBLIC BANK GMBH
            BRUNNENSTRASSE 19-21
            10119 BERLIN
            BIC
            01 Mai 2024 - 17 Nov. 2026
            DE02100123450000000001
            TRBKDEBBXXX
            Max Mustermann
            DATUM
            Musterweg 1
            12345 Musterstadt
            IBAN
            KONTOÜBERSICHT
            PRODUKT
            ANFANGSSALDO
            ZAHLUNGSEINGANG
            ZAHLUNGSAUSGANG
            ENDSALDO
            Cashkonto
            0,00 €
            500.000,00 €
            499.886,08 €
            113,92 €
            UMSATZÜBERSICHT
            """;

    private static final String SINGLE_MONTH_STATEMENT = """
            TRADE REPUBLIC BANK GMBH
            BRUNNENSTRASSE 19-21
            10119 BERLIN
            Max Mustermann
            Musterweg 1, 12345 Musterstadt
            DATUM 01 Okt. 2026 - 31 Okt. 2026
            IBAN DE02100123450000000001
            BIC TRBKDEBBXXX
            KONTOÜBERSICHT
            PRODUKT ANFANGSSALDO ZAHLUNGSEINGANG ZAHLUNGSAUSGANG ENDSALDO
            Cashkonto 1.000,00 € 500,00 € 250,00 € 1.250,00 €
            """;

    private final TradeRepublicCashStatementParser parser = new TradeRepublicCashStatementParser();

    @Test
    void supportsOnlyTradeRepublicCashStatements() {
        assertThat(parser.supports(MULTI_YEAR_STATEMENT)).isTrue();
        assertThat(parser.supports(SINGLE_MONTH_STATEMENT)).isTrue();
        assertThat(parser.supports("TRADE REPUBLIC VERMÖGENSÜBERSICHT PORTFOLIO")).isFalse();
    }

    @Test
    void parsesMultiYearCashStatementWithoutDistortingMonthlyCashflows() {
        List<StatementEntry> entries = parser.parse(MULTI_YEAR_STATEMENT);

        assertThat(entries).hasSize(1);
        StatementEntry entry = entries.get(0);
        assertThat(entry.bank()).isEqualTo("Trade Republic");
        assertThat(entry.accountLabel()).isEqualTo("Cashkonto");
        assertThat(entry.suggestedType()).isEqualTo(AccountType.SAVINGS);
        assertThat(entry.iban()).isEqualTo("DE02100123450000000001");
        assertThat(entry.accountHolder()).isEqualTo("Max Mustermann");
        assertThat(entry.closingDate()).isEqualTo(LocalDate.of(2026, 11, 17));
        assertThat(entry.closingBalance()).isEqualByComparingTo("113.92");
        assertThat(entry.inflow()).isNull();
        assertThat(entry.outflow()).isNull();
        assertThat(entry.importKey()).isEqualTo("IBAN|DE02100123450000000001");
    }

    @Test
    void parsesSingleMonthCashStatementWithMonthlyFlows() {
        List<StatementEntry> entries = parser.parse(SINGLE_MONTH_STATEMENT);

        assertThat(entries).hasSize(1);
        StatementEntry entry = entries.get(0);
        assertThat(entry.closingDate()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(entry.closingBalance()).isEqualByComparingTo("1250.00");
        assertThat(entry.inflow()).isEqualByComparingTo("500.00");
        assertThat(entry.outflow()).isEqualByComparingTo("250.00");
    }

    @Test
    void rejectsUnsupportedText() {
        assertThatThrownBy(() -> parser.parse("Random bank statement"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
