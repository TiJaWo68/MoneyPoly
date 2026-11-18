package de.in.mp.energy.bill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class LogoEnergieBillParserTest {

    private final LogoEnergieBillParser parser = new LogoEnergieBillParser();

    @Test
    void parsesSyntheticLogoEnergieInvoiceWithPeriodsAndPriceLines() {
        String text = """
                LogoEnergie GmbH · Postfach 1359 · 53863 Euskirchen
                Max Mustermann
                Musterweg 1
                12345 Musterstadt
                E-Mail: service@example-energy.test
                Kunden-Nr. 123.456.789-0
                Rechnungsdatum  17. November 2026
                Rechnungsnummer  LM2026/00012345
                Rechnung
                Abrechnungszeitraum:  01.12.2025 - 30.11.2026
                Strom 0 kWh (0) 3.650 kWh (365) 955,50 181,55 (19%) 1.137,05
                Gesamtbetrag 955,50 181,55 1.137,05
                bis 15.11.2026 gezahlt (Abschlagsanforderungen enthielten 150,00 € USt) -900,00
                zu zahlender Betrag, fällig am 01.12.2026 237,05
                Neuer Abschlag Netto € USt % USt € Brutto €
                Strom 88,24 19 16,76 105,00
                Zählernummer MTR-SYNTH-001
                Zeitraum Zählerstand in kWh Differenz Menge
                alt neu in kWh
                01.12.2025 - 31.05.2026 10.000 11.800 2) 1.800 1.800 kWh
                01.06.2026 - 30.09.2026 11.800 12.650 1) 850 850 kWh
                01.10.2026 - 30.11.2026 12.650 13.650 3) 1.000 1.000 kWh
                1) Zählerstand vom Marktpartner übermittelt. (Geschätzter Wert)
                2) Zählerstand vom Marktpartner übermittelt. (Abgelesener Wert)
                3) Der Zählerstand wurde programmseitig geschätzt.
                Ihre Kosten werden im Tarif "LogoStrom Basis" wie folgt berechnet:
                01.12.2025 - 31.05.2026 AP LogoStrom 23,000 Ct/kWh 1.800 kWh 19% 414,00 €
                01.06.2026 - 30.09.2026 AP LogoStrom 23,000 Ct/kWh 850 kWh 19% 195,50 €
                01.10.2026 - 30.11.2026 AP LogoStrom 23,000 Ct/kWh 1.000 kWh 19% 230,00 €
                01.12.2025 - 30.11.2026 GP LogoStrom 116,00 €/Jahr 365 Tage 19% 116,00 €
                """;

        assertThat(parser.supports(text)).isTrue();
        UtilityBill bill = parser.parse(text);

        assertThat(bill.providerName()).isEqualTo("LogoEnergie GmbH");
        assertThat(bill.providerEmail()).isEqualTo("service@example-energy.test");
        assertThat(bill.customerNumber()).isEqualTo("123.456.789-0");
        assertThat(bill.invoiceNumber()).isEqualTo("LM2026/00012345");
        assertThat(bill.invoiceDate()).isEqualTo(LocalDate.of(2026, 11, 17));
        assertThat(bill.periodStart()).isEqualTo(LocalDate.of(2025, 12, 1));
        assertThat(bill.periodEnd()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(bill.billedDays()).isEqualTo(365);
        assertThat(bill.meterSerialNumber()).isEqualTo("MTR-SYNTH-001");
        assertThat(bill.tariffName()).isEqualTo("LogoStrom Basis");
        assertThat(bill.totalConsumptionKwh()).isEqualByComparingTo("3650");
        assertThat(bill.totalNetEur()).isEqualByComparingTo("955.50");
        assertThat(bill.vatRatePercent()).isEqualByComparingTo("19");
        assertThat(bill.totalVatEur()).isEqualByComparingTo("181.55");
        assertThat(bill.totalGrossEur()).isEqualByComparingTo("1137.05");
        assertThat(bill.prepaymentsGrossEur()).isEqualByComparingTo("900.00");
        assertThat(bill.amountDueGrossEur()).isEqualByComparingTo("237.05");
        assertThat(bill.newInstallmentNetEur()).isEqualByComparingTo("88.24");
        assertThat(bill.newInstallmentGrossEur()).isEqualByComparingTo("105.00");

        assertThat(bill.periods()).hasSize(3);
        assertThat(bill.periods().get(0).source()).isEqualTo(UtilityBillReadingSource.METER_READ);
        assertThat(bill.periods().get(1).source()).isEqualTo(UtilityBillReadingSource.ESTIMATED);
        assertThat(bill.periods().get(2).source()).isEqualTo(UtilityBillReadingSource.ESTIMATED);
        assertThat(bill.periods().get(2).consumptionKwh()).isEqualByComparingTo("1000");

        assertThat(bill.priceLines()).hasSize(4);
        assertThat(bill.priceLines().get(0).expectedNetEur()).isEqualByComparingTo("414.00");
        assertThat(bill.priceLines().get(3).expectedNetEur()).isEqualByComparingTo("116.00");
    }

    @Test
    void rejectsNonLogoEnergieText() {
        assertThat(parser.supports("Some other utility invoice")).isFalse();
        assertThatThrownBy(() -> parser.parse("Some other utility invoice"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
