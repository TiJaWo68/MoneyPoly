package de.in.mp.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.in.mp.finance.AccountType;

class ScalableFinanzstatusStatementParserTest {

    private final ScalableFinanzstatusStatementParser parser = new ScalableFinanzstatusStatementParser();

    @Test
    void parsesTagesgeldFinanzstatusPdfText() {
        String text = """
                Scalable Capital Bank GmbH • Seitzstraße 8e, 80538 München
                Till Woitendorf
                Tannenbergsiedlung 10
                18258 Schwaan
                Deutschland
                Datum
                23.11.2026
                Seite 1/1
                Kategorie Anteil Positionswert
                Guthaben 100,00 % 78.189,72 EUR
                Gesamt 78.189,72 EUR
                Finanzstatus
                per 22.11.2026
                Gesamtübersicht
                Guthaben
                Typ Saldo
                Portfolio
                Tagesgeld 78.189,72 EUR
                Gesamt 78.189,72 EUR
                """;

        assertThat(parser.supports(text)).isTrue();
        List<StatementEntry> entries = parser.parse(text);
        assertThat(entries).hasSize(1);

        StatementEntry entry = entries.get(0);
        assertThat(entry.bank()).isEqualTo("Scalable Capital");
        assertThat(entry.accountLabel()).isEqualTo("Tagesgeld");
        assertThat(entry.suggestedType()).isEqualTo(AccountType.SAVINGS);
        assertThat(entry.accountHolder()).isEqualTo("Till Woitendorf");
        assertThat(entry.closingDate()).isEqualTo(LocalDate.of(2026, 11, 22));
        assertThat(entry.closingBalance()).isEqualByComparingTo(new BigDecimal("78189.72"));
        assertThat(entry.importKey()).isEqualTo("Scalable Capital|till woitendorf|tagesgeld");
    }

    @Test
    void parsesBrokerageFinanzstatusPdfTextAsDepotStatement() {
        String brokerageText = """
                Scalable Capital Bank GmbH • Seitzstraße 8e, 80538 München
                Till Woitendorf
                Tannenbergsiedlung 10
                18258 Schwaan
                Deutschland
                Datum
                23.11.2026
                Seite 1/2
                Kategorie Anteil Positionswert
                Guthaben 0,02 % 14,53 EUR
                Aktien 7,63 % 4.838,18 EUR
                Exchange Traded Funds 92,35 % 58.563,18 EUR
                Gesamt 63.415,89 EUR
                Finanzstatus
                per 22.11.2026
                Gesamtübersicht
                Seite 2/2
                Stückzahl Wertpapier Positionswert
                10.000000 Advanced Micro Devices US0079031078 3.915,00 EUR
                Gesamt 4.838,18 EUR
                """;

        assertThat(parser.supports(brokerageText)).isTrue();
        List<StatementEntry> entries = parser.parse(brokerageText);
        assertThat(entries).hasSize(1);

        StatementEntry entry = entries.get(0);
        assertThat(entry.bank()).isEqualTo("Scalable Capital");
        assertThat(entry.accountLabel()).isEqualTo("Depot");
        assertThat(entry.suggestedType()).isEqualTo(AccountType.INVESTMENT);
        assertThat(entry.accountHolder()).isEqualTo("Till Woitendorf");
        assertThat(entry.closingDate()).isEqualTo(LocalDate.of(2026, 11, 22));
        assertThat(entry.closingBalance()).isEqualByComparingTo(new BigDecimal("63415.89"));
        assertThat(entry.importKey()).isEqualTo("Scalable Capital|till woitendorf|depot");
    }
}
