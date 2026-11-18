package de.in.mp.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class ScalableFinanzstatusParserTest {

    private final ScalableFinanzstatusParser parser = new ScalableFinanzstatusParser();

    @Test
    void parsesAktienAndEtfsAcrossMultiLineAndTabularLayoutsAndIgnoresCash() {
        String text = """
                Scalable Capital Bank GmbH • Seitzstraße 8e, 80538 München
                Max Mustermann
                Musterweg 1
                12345 Musterstadt
                Finanzstatus
                per 17.11.2026
                Gesamtübersicht
                Kategorie Anteil Positionswert
                Guthaben 0,01 % 7,19 EUR
                Aktien 7,00 % 4.609,00 EUR
                Exchange Traded Funds 92,99 % 61.207,40 EUR
                Gesamt 65.823,59 EUR
                Guthaben
                Portfolio Depot Typ Saldo
                BROKER 9999999999 7,19 EUR
                Gesamt 7,19 EUR
                Aktien
                Stückzahl Wertpapier Positionswert
                10.000000 Example Tech Corp 4.609,00 EUR
                US0000000010
                Gesamt 4.609,00 EUR
                Exchange Traded Funds
                Stückzahl Wertpapier Positionswert
                159.000000 Example Core Europe 600 (Acc)
                LU0000000020 51.213,90 EUR
                869.000000 Example S&P
                500 Equal Weight (Acc) LU0000000030 9.993,50 EUR
                Gesamt 61.207,40 EUR
                Bitte überprüfen Sie die Informationen auf Richtigkeit.
                """;

        assertThat(parser.supports(text)).isTrue();
        List<ImportedDepotPosition> entries = parser.parse(text);

        assertThat(entries).hasSize(3);

        ImportedDepotPosition stock = entries.get(0);
        assertThat(stock.name()).isEqualTo("Example Tech Corp");
        assertThat(stock.isin()).isEqualTo("US0000000010");
        assertThat(stock.quantity()).isEqualByComparingTo("10.000000");
        assertThat(stock.marketValueEur()).isEqualByComparingTo("4609.00");
        assertThat(stock.unitPriceEur()).isEqualByComparingTo("460.9000");

        ImportedDepotPosition etf1 = entries.get(1);
        assertThat(etf1.name()).isEqualTo("Example Core Europe 600 (Acc)");
        assertThat(etf1.isin()).isEqualTo("LU0000000020");
        assertThat(etf1.quantity()).isEqualByComparingTo("159");
        assertThat(etf1.marketValueEur()).isEqualByComparingTo("51213.90");
        assertThat(etf1.unitPriceEur()).isEqualByComparingTo("322.1000");

        ImportedDepotPosition etf2 = entries.get(2);
        assertThat(etf2.name()).isEqualTo("Example S&P 500 Equal Weight (Acc)");
        assertThat(etf2.isin()).isEqualTo("LU0000000030");
        assertThat(etf2.quantity()).isEqualByComparingTo("869");
        assertThat(etf2.marketValueEur()).isEqualByComparingTo("9993.50");
        assertThat(etf2.unitPriceEur()).isEqualByComparingTo("11.5000");
    }

    @Test
    void rejectsScalableKontoauszugAndOtherFormats() {
        assertThat(parser.supports("Scalable Capital Kontoauszug Verrechnungskonto DE00...")).isFalse();
        assertThat(parser.supports("Trade Republic Vermögensübersicht Brokerage")).isFalse();
        assertThatThrownBy(() -> parser.parse("Not a Finanzstatus"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
