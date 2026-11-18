package de.in.mp.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.in.mp.finance.AccountType;

/**
 * Tests {@link TradeRepublicOverviewStatementParser} with synthetic Trade
 * Republic wealth overview statements.
 */
class TradeRepublicOverviewStatementParserTest {

    private static final String OVERVIEW = """
            TRADE REPUBLIC BANK GMBH
            BRUNNENSTRASSE 19-21
            10119 BERLIN
            Max Mustermann
            DATUM
            Musterweg 1
            12345 Musterstadt
            DEPOT
            18.11.2026
            0123456789
            VERMÖGENSÜBERSICHT
            zum 18.11.2026
            PORTFOLIO
            KURSWERT IN EUR
            Brokerage
            113.710,08
            Cash
            113,92
            GESAMT
            113.824,00 EUR
            BROKERAGE
            """;

    private final TradeRepublicOverviewStatementParser parser = new TradeRepublicOverviewStatementParser();

    @Test
    void supportsTradeRepublicWealthOverview() {
        assertThat(parser.supports(OVERVIEW)).isTrue();
        assertThat(parser.supports("TRADE REPUBLIC KONTOÜBERSICHT Cashkonto")).isFalse();
    }

    @Test
    void extractsSeparateBrokerageAndCashEntries() {
        List<StatementEntry> entries = parser.parse(OVERVIEW);

        assertThat(entries).hasSize(2);

        StatementEntry brokerage = entries.get(0);
        assertThat(brokerage.bank()).isEqualTo("Trade Republic");
        assertThat(brokerage.accountLabel()).isEqualTo("Brokerage");
        assertThat(brokerage.suggestedType()).isEqualTo(AccountType.INVESTMENT);
        assertThat(brokerage.accountNumber()).isEqualTo("0123456789");
        assertThat(brokerage.accountHolder()).isEqualTo("Max Mustermann");
        assertThat(brokerage.closingDate()).isEqualTo(LocalDate.of(2026, 11, 18));
        assertThat(brokerage.closingBalance()).isEqualByComparingTo("113710.08");
        assertThat(brokerage.importKey()).isEqualTo("TradeRepublic|0123456789|BROKERAGE");

        StatementEntry cash = entries.get(1);
        assertThat(cash.accountLabel()).isEqualTo("Cashkonto");
        assertThat(cash.suggestedType()).isEqualTo(AccountType.SAVINGS);
        assertThat(cash.closingBalance()).isEqualByComparingTo("113.92");
        assertThat(cash.importKey()).isEqualTo("TradeRepublic|0123456789|CASH");
    }

    @Test
    void extractsSingleLinePdfBoxPortfolioSummary() {
        String singleLine = """
                TRADE REPUBLIC BANK GMBH
                Max Mustermann
                Musterweg 1
                12345 Musterstadt
                DEPOT 0123456789
                VERMÖGENSÜBERSICHT
                zum 18.11.2026
                PORTFOLIO KURSWERT IN EUR
                Brokerage 113.710,08
                Cash 113,92
                GESAMT 113.824,00 EUR
                """;
        List<StatementEntry> entries = parser.parse(singleLine);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).closingBalance()).isEqualByComparingTo("113710.08");
        assertThat(entries.get(1).closingBalance()).isEqualByComparingTo("113.92");
    }
}
