package de.in.mp.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class TradeRepublicStatementParserTest {

    private static final String LINE_WRAPPED_STATEMENT = """
            TRADE REPUBLIC BANK GMBH BRUNNENSTRASSE 19-21 10119 BERLIN
            DATUM 14.11.2026
            DEPOT 0000000001
            PERSON A
            VERMÖGENSÜBERSICHT
            zum 14.11.2026
            PORTFOLIO KURSWERT IN EUR
            Brokerage 12.345,67
            Cash 100,00
            GESAMT 12.445,67 EUR
            BROKERAGE
            Aufstellung über die Brokerage Wertpapiere in Deinem Depot 0000000001 zum 14.11.2026.
            STK. / NOMINALE WERTPAPIERBEZEICHNUNG KURS PRO STÜCK KURSWERT IN EUR
            37,207772 Stk. Example Gold USD (Acc)
            ISIN: XY0000000001
            Wertpapierrechnung in Deutschland
            Lagerland: Irland
            68,15
            13.11.2026
            2.535,52
            1,591779 Stk. Sample Holding Ltd.
            Reg.Shares DL-,000003125
            ISIN: XY0000000002
            10,64
            13.11.2026
            16,94
            126,215889 Stk. Alpha Health AS
            Indeh.Akt.B (Sp. ADRs)/1 DK 10
            ISIN: XY0000000003
            37,44
            13.11.2026
            4.725,52
            ANZAHL POSITIONEN: 3 7.277,98 EUR
            CASH
            Aufstellung über das Cash in deinem Konto zum 14.11.2026.
            Cashkonto 100,00 EUR
            """;

    private static final String SINGLE_LINE_COLUMNS_STATEMENT = """
            TRADE REPUBLIC BANK GMBH
            VERMÖGENSÜBERSICHT
            BROKERAGE
            STK. / NOMINALE WERTPAPIERBEZEICHNUNG KURS PRO STÜCK KURSWERT IN EUR
            331,417713 Stk. Core Stoxx Example 600 EUR (Acc) 328,30 13.11.2026 108.804,44
            ISIN: XY0000000004
            Erstellt am 2026-11-14 23:15:43 Europe/Berlin (UTC+01:00) Seite 1 von 2
            TRADE REPUBLIC BANK GMBH
            BROKERAGE
            STK. / NOMINALE WERTPAPIERBEZEICHNUNG KURS PRO STÜCK KURSWERT IN EUR
            0,120383 Stk. Sample Tech Inc. 240,20 28,92
            Registered Shares DL -,01 13.11.2026
            ISIN: XY0000000005
            ANZAHL POSITIONEN: 2 108.833,36 EUR
            """;

    private final TradeRepublicStatementParser parser = new TradeRepublicStatementParser();

    @Test
    void supportsTradeRepublicWealthOverviewOnly() {
        assertThat(parser.supports(LINE_WRAPPED_STATEMENT)).isTrue();
        assertThat(parser.supports("TARGOBANK F I N A N Z S T A T U S")).isFalse();
        assertThat(parser.supports(null)).isFalse();
    }

    @Test
    void parsesLineWrappedBrokerageEntriesIgnoringNominalSubtitles() {
        List<ImportedDepotPosition> result = parser.parse(LINE_WRAPPED_STATEMENT);

        assertThat(result).hasSize(3);

        ImportedDepotPosition gold = result.get(0);
        assertThat(gold.name()).isEqualTo("Example Gold USD (Acc)");
        assertThat(gold.isin()).isEqualTo("XY0000000001");
        assertThat(gold.quantity()).isEqualByComparingTo("37.207772");
        assertThat(gold.unitPriceEur()).isEqualByComparingTo("68.15");
        assertThat(gold.marketValueEur()).isEqualByComparingTo("2535.52");

        ImportedDepotPosition holding = result.get(1);
        assertThat(holding.name()).isEqualTo("Sample Holding Ltd.");
        assertThat(holding.isin()).isEqualTo("XY0000000002");
        assertThat(holding.quantity()).isEqualByComparingTo("1.591779");
        assertThat(holding.unitPriceEur()).isEqualByComparingTo("10.64");
        assertThat(holding.marketValueEur()).isEqualByComparingTo("16.94");

        ImportedDepotPosition alpha = result.get(2);
        assertThat(alpha.isin()).isEqualTo("XY0000000003");
        assertThat(alpha.quantity()).isEqualByComparingTo("126.215889");
        assertThat(alpha.unitPriceEur()).isEqualByComparingTo("37.44");
    }

    @Test
    void parsesTabularSingleLineColumnsAcrossPageBreaks() {
        List<ImportedDepotPosition> result = parser.parse(SINGLE_LINE_COLUMNS_STATEMENT);

        assertThat(result).hasSize(2);

        ImportedDepotPosition etf = result.get(0);
        assertThat(etf.name()).isEqualTo("Core Stoxx Example 600 EUR (Acc)");
        assertThat(etf.isin()).isEqualTo("XY0000000004");
        assertThat(etf.quantity()).isEqualByComparingTo("331.417713");
        assertThat(etf.unitPriceEur()).isEqualByComparingTo("328.30");
        assertThat(etf.marketValueEur()).isEqualByComparingTo("108804.44");

        ImportedDepotPosition tech = result.get(1);
        assertThat(tech.name()).isEqualTo("Sample Tech Inc.");
        assertThat(tech.isin()).isEqualTo("XY0000000005");
        assertThat(tech.quantity()).isEqualByComparingTo("0.120383");
        assertThat(tech.unitPriceEur()).isEqualByComparingTo("240.20");
        assertThat(tech.marketValueEur()).isEqualByComparingTo("28.92");
    }

    @Test
    void rejectsTextWithoutBrokeragePositions() {
        assertThatThrownBy(() -> parser.parse("TRADE REPUBLIC BANK GMBH\nVERMÖGENSÜBERSICHT\nCASH\n100,00 EUR"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
