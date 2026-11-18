package de.in.mp.energy.bill;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.in.mp.energy.bill.UtilityBillComplaintGenerator.ComplaintDraft;
import de.in.mp.energy.bill.UtilityBillComplaintGenerator.ComplaintOptions;

class UtilityBillComplaintGeneratorTest {

    @Test
    void generatesPoliteGermanComplaintWithDiscrepancyInstallmentAndSmartMeterHint() {
        UtilityBillVerificationResult result = syntheticDiscrepancyResult();
        ComplaintOptions opts = new ComplaintOptions(
                new BigDecimal("13210"),
                LocalDate.of(2026, 11, 18),
                true,
                "Max Mustermann");

        ComplaintDraft draft = UtilityBillComplaintGenerator.generate(result, opts, Locale.GERMANY);

        assertThat(draft.subject())
                .contains("LM2026/00012345")
                .contains("123.456.789-0")
                .contains("MTR-SYNTH-100");
        assertThat(draft.body())
                .contains("13.650 kWh")
                .contains("13.150 kWh")
                .contains("+500 kWh")
                .contains("13.210 kWh")
                .contains("18.11.2026")
                .contains("iMSys")
                .contains("105,00 €")
                .contains("94,75 €")
                .contains("Vorbehalt der Rückforderung")
                .contains("Max Mustermann");
    }

    @Test
    void generatesEnglishComplaintWithoutSmartMeterHintWhenUnchecked() {
        UtilityBillVerificationResult result = syntheticDiscrepancyResult();

        ComplaintDraft draft = UtilityBillComplaintGenerator.generate(
                result, ComplaintOptions.empty(), Locale.ENGLISH);

        assertThat(draft.subject()).contains("LM2026/00012345");
        assertThat(draft.body())
                .contains("13,650 kWh")
                .contains("13,150 kWh")
                .doesNotContain("iMSys")
                .contains("[Your Name]");
    }

    @Test
    void buildsPercentEncodedGmailComposeAndMailtoUrls() {
        ComplaintDraft draft = new ComplaintDraft("Betreff Test", "Hallo Team,\nBitte prüfen.");

        String gmailUrl = UtilityBillComplaintGenerator.buildGmailComposeUrl("service@example-energy.test", draft);
        String mailtoUrl = UtilityBillComplaintGenerator.buildMailtoUrl("service@example-energy.test", draft);

        assertThat(gmailUrl)
                .startsWith("https://mail.google.com/mail/?view=cm&fs=1")
                .contains("to=service%40example-energy.test")
                .contains("su=Betreff%20Test")
                .contains("body=Hallo%20Team%2C%0ABitte%20pr%C3%BCfen.")
                .doesNotContain("+");
        assertThat(mailtoUrl)
                .startsWith("mailto:service%40example-energy.test?subject=Betreff%20Test&body=Hallo%20Team%2C%0ABitte%20pr%C3%BCfen.");
    }

    private UtilityBillVerificationResult syntheticDiscrepancyResult() {
        UtilityBill bill = new UtilityBill(
                "LogoEnergie GmbH",
                "service@example-energy.test",
                "123.456.789-0",
                "LM2026/00012345",
                LocalDate.of(2026, 11, 17),
                LocalDate.of(2025, 12, 1),
                LocalDate.of(2026, 11, 30),
                365,
                "MTR-SYNTH-100",
                "LogoStrom Basis",
                new BigDecimal("3650"),
                new BigDecimal("955.50"),
                new BigDecimal("19"),
                new BigDecimal("181.55"),
                new BigDecimal("1137.05"),
                new BigDecimal("900.00"),
                new BigDecimal("237.05"),
                new BigDecimal("88.24"),
                new BigDecimal("105.00"),
                List.of(new UtilityBillPeriod(
                        LocalDate.of(2026, 6, 1), LocalDate.of(2026, 11, 30),
                        new BigDecimal("11800"), new BigDecimal("13650"), new BigDecimal("1850"),
                        UtilityBillReadingSource.ESTIMATED, "3)")),
                List.of());

        PeriodComparison periodComp = new PeriodComparison(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 11, 30), 183,
                new BigDecimal("11800"), new BigDecimal("13650"), new BigDecimal("1850"),
                UtilityBillReadingSource.ESTIMATED, "3)",
                new BigDecimal("11800"), new BigDecimal("13150"), new BigDecimal("1350"),
                new BigDecimal("500.0"), new BigDecimal("115.00"), new BigDecimal("136.85"),
                PeriodMatchQuality.EXACT, true, true);

        return new UtilityBillVerificationResult(
                bill,
                UUID.randomUUID(),
                "Strom Gesamt",
                true,
                BillVerdict.DISCREPANCY,
                new ArithmeticCheckResult(true, true, true, true, true, true, new BigDecimal("955.50"), BigDecimal.ZERO),
                null,
                new InstallmentEvaluation(
                        new BigDecimal("3650"), new BigDecimal("3150"),
                        new BigDecimal("105.00"), new BigDecimal("94.75"), new BigDecimal("10.25")),
                List.of(periodComp),
                new BigDecimal("3150"),
                new BigDecimal("3650"),
                new BigDecimal("500.0"),
                new BigDecimal("115.00"),
                new BigDecimal("136.85"),
                new BigDecimal("13150"),
                new BigDecimal("500.0"),
                1);
    }
}
