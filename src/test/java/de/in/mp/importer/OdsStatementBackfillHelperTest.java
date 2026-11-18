package de.in.mp.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import de.in.mp.finance.AccountType;
import de.in.mp.importer.OdsStatementBackfillHelper.BackfillConfig;

class OdsStatementBackfillHelperTest {

    private static BackfillConfig sampleConfig() {
        Properties props = new Properties();
        props.setProperty("iban.DE02120300000000202051", "Bank A / Giro");
        props.setProperty("accountNumber.1111111111", "Bank B / Spar");
        props.setProperty("accountNumber.2222222222", "Bank B / Spar");
        props.setProperty("transferTracked.DE99500105175407324932", "Bank C /");
        props.setProperty("transferTracked.DE8800730100560190222172", "Bank D / Tagesgeld");
        props.setProperty("c24Owner.max", "C24 Max");
        props.setProperty("c24LabelOverride.max.tagesgeldpocket", "C24 Max / Pocket");
        return BackfillConfig.fromProperties(props);
    }

    @Test
    void mapsConfiguredIbansAccountNumbersAndLabelOverrides() {
        OdsStatementBackfillHelper helper = new OdsStatementBackfillHelper(sampleConfig());

        StatementEntry giro = new StatementEntry(
                "C24", "C24 Smartkonto Girokonto", AccountType.CHECKING,
                "DE02120300000000202051", null, "Max Mustermann",
                LocalDate.of(2023, 9, 30), new BigDecimal("1000.33"),
                null, null, "IBAN|DE02120300000000202051");

        StatementEntry pocket = new StatementEntry(
                "C24", "Tagesgeldpocket Tagesgeld", AccountType.SAVINGS,
                null, null, "Max Mustermann",
                LocalDate.of(2023, 9, 30), new BigDecimal("25000.00"),
                null, null, "C24|max mustermann|tagesgeldpocket");

        StatementEntry savings1 = new StatementEntry(
                "Targobank", "EUR TAGESGELDKONTO", AccountType.SAVINGS,
                null, "1111111111", "Max Mustermann",
                LocalDate.of(2022, 10, 31), new BigDecimal("42000.00"),
                null, null, "Targobank|1111111111");

        StatementEntry savings2 = new StatementEntry(
                "Targobank", "SPARBUCH", AccountType.SAVINGS,
                null, "2222222222", "Max Mustermann",
                LocalDate.of(2022, 10, 31), new BigDecimal("1.00"),
                null, null, "Targobank|2222222222");

        assertThat(helper.mapToOdsColumn(giro)).contains("Bank A / Giro");
        assertThat(helper.mapToOdsColumn(pocket)).contains("C24 Max / Pocket");
        assertThat(helper.mapToOdsColumn(savings1)).contains("Bank B / Spar");
        assertThat(helper.mapToOdsColumn(savings2)).contains("Bank B / Spar");

        Map<LocalDate, Map<String, BigDecimal>> aggregated = helper.aggregateByMonthEnd(
                List.of(giro, pocket, savings1, savings2),
                LocalDate.of(2022, 9, 30),
                LocalDate.of(2024, 7, 31));

        assertThat(aggregated.get(LocalDate.of(2022, 10, 31)))
                .containsEntry("Bank B / Spar", new BigDecimal("42001.00"));
        assertThat(aggregated.get(LocalDate.of(2023, 9, 30)))
                .containsEntry("Bank A / Giro", new BigDecimal("1000.33"))
                .containsEntry("C24 Max / Pocket", new BigDecimal("25000.00"));
    }

    @Test
    void extractsTargobankTransfersFromSyntheticStatement() {
        String sample = """
                F I N A N Z S T A T U S vom 01.05.2023 - 31.05.2023
                30.04 SO 100.000,00ANFANGSSALDO
                08.05 MO 11.000,00 89.000,00ÜBERWEISUNG TARGO CET
                BIC :BANKDEFFXXX IBAN:DE99500105175407324932
                08.05 MO 89.000,00ERÖFFNUNG DAUERAUFTRAG Nr.0300008 TARGO CET
                15.05 MO 40.000,00 49.000,00ÜBERWEISUNG
                IBAN DE99500105175407324932 BIC BANKDEFFXXX
                29.05 MO 10.000,00 39.000,00SEPA ÜBERWEISUNG TARGO CET
                BIC:BANKESMMXXX IBAN:DE8800730100560190222172
                31.05 MI 39.000,00ENDSALDO
                """;
        OdsStatementBackfillHelper helper = new OdsStatementBackfillHelper(sampleConfig());
        var events = helper.extractTargobankTransfers(sample);
        assertThat(events).hasSize(3);
        assertThat(events.get(0).targetColumn()).isEqualTo("Bank C /");
        assertThat(events.get(0).delta()).isEqualByComparingTo("11000.00");
        assertThat(events.get(1).delta()).isEqualByComparingTo("40000.00");
        assertThat(events.get(2).targetColumn()).isEqualTo("Bank D / Tagesgeld");
        assertThat(events.get(2).delta()).isEqualByComparingTo("10000.00");
    }

    @Test
    void emitsZeroOnceTransferTrackedAccountDrainsAfterPositiveBalance() throws Exception {
        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("backfill-test");
        java.nio.file.Path pdf1 = tempDir.resolve("2023-05.pdf");
        java.nio.file.Path pdf2 = tempDir.resolve("2024-01.pdf");
        java.nio.file.Files.writeString(pdf1, "may");
        java.nio.file.Files.writeString(pdf2, "jan");

        String mayText = """
                TARGOBANK AG
                F I N A N Z S T A T U S vom 01.05.2023 - 31.05.2023
                30.04 SO 50.000,00ANFANGSSALDO
                08.05 MO 20.000,00 30.000,00ÜBERWEISUNG
                IBAN:DE99500105175407324932
                31.05 MI 30.000,00ENDSALDO
                """;
        String janText = """
                C24 Bank GmbH
                Max Mustermann
                Musterweg 1
                12345 Musterstadt
                C24 Smartkonto
                Kontoauszug 01/2024
                01.01.2024 - 31.01.2024
                03.01. 03.01. Überweisung
                IBAN: DE99500105175407324932 / BIC: BANKDEFFXXX
                + 20.000,00 €
                Zusammenfassung
                """;
        PdfTextExtractor stubExtractor = new PdfTextExtractor() {
            @Override
            public String extract(byte[] pdfBytes) {
                String s = new String(pdfBytes, java.nio.charset.StandardCharsets.UTF_8);
                return "may".equals(s) ? mayText : janText;
            }
        };

        OdsStatementBackfillHelper helper = new OdsStatementBackfillHelper(
                stubExtractor, List.of(), sampleConfig());
        Map<LocalDate, Map<String, BigDecimal>> balances = helper.reconstructTransferBalances(
                tempDir, LocalDate.of(2023, 5, 31), LocalDate.of(2024, 2, 29));

        assertThat(balances.get(LocalDate.of(2023, 5, 31))).containsEntry("Bank C /", new BigDecimal("20000.00"));
        assertThat(balances.get(LocalDate.of(2024, 1, 31))).containsEntry("Bank C /", new BigDecimal("0.00"));
        assertThat(balances.get(LocalDate.of(2024, 2, 29))).containsEntry("Bank C /", new BigDecimal("0.00"));
    }
}
