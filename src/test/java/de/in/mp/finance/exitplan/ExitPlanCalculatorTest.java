package de.in.mp.finance.exitplan;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class ExitPlanCalculatorTest {

    private final ExitPlanCalculator calculator = new ExitPlanCalculator();

    @Test
    void capitalPreservationWithdrawsOnlyReturnAndKeepsPrincipalIntact() {
        ExitPlanPersonInput person = new ExitPlanPersonInput(
                "Person A",
                62,
                62,
                67,
                new BigDecimal("1800.00"),
                BigDecimal.ZERO,
                new BigDecimal("400.00"),
                false);

        ExitPlanRequest request = new ExitPlanRequest(
                new BigDecimal("300000.00"),
                new BigDecimal("4.00"),
                ExitPlanMode.CAPITAL_PRESERVATION,
                85,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                2026,
                person,
                null);

        ExitPlanResult result = calculator.calculate(request);

        // 300,000 * 4% = 12,000/yr return minus 4,800/yr bridge KV = 7,200/yr net living budget
        assertThat(result.sustainableAnnualLivingBudget()).isEqualByComparingTo("7200.00");
        assertThat(result.averageMonthlyLivingBudget()).isEqualByComparingTo("600.00");
        assertThat(result.exhaustedAtPrimaryAge()).isNull();
        assertThat(result.capitalAtTargetAge()).isEqualByComparingTo("300000.00");
        assertThat(result.rows()).allSatisfy(row ->
                assertThat(row.endCapital()).isEqualByComparingTo("300000.00"));
    }

    @Test
    void depleteToAgeComputesConstantBudgetAcrossBridgeAndPensionPhase() {
        ExitPlanPersonInput person = new ExitPlanPersonInput(
                "Person A",
                60,
                60,
                67,
                new BigDecimal("2000.00"),
                BigDecimal.ZERO,
                new BigDecimal("500.00"),
                false);

        ExitPlanRequest request = new ExitPlanRequest(
                new BigDecimal("300000.00"),
                new BigDecimal("4.00"),
                ExitPlanMode.DEPLETE_TO_AGE,
                85,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                2026,
                person,
                null);

        ExitPlanResult result = calculator.calculate(request);

        assertThat(result.sustainableAnnualLivingBudget()).isGreaterThan(new BigDecimal("24000.00"));
        assertThat(result.capitalAtTargetAge()).isBetween(BigDecimal.ZERO, new BigDecimal("5.00"));
        assertThat(result.exhaustedAtPrimaryAge())
                .satisfiesAnyOf(
                        age -> assertThat(age).isNull(),
                        age -> assertThat(age).isGreaterThan(85));
        // Before pension (age 60..66) asset withdrawal exceeds pension years (67+)
        ExitPlanYearRow age62Row = result.rows().stream()
                .filter(r -> r.primaryAge() == 62)
                .findFirst()
                .orElseThrow();
        ExitPlanYearRow age70Row = result.rows().stream()
                .filter(r -> r.primaryAge() == 70)
                .findFirst()
                .orElseThrow();
        assertThat(age62Row.assetWithdrawal()).isGreaterThan(age70Row.assetWithdrawal());
        assertThat(age62Row.healthInsuranceCost()).isEqualByComparingTo("6000.00");
        assertThat(age70Row.healthInsuranceCost()).isEqualByComparingTo("0.00");
    }

    @Test
    void depleteToAgeRespectsTargetResidualCapitalBuffer() {
        ExitPlanPersonInput person = new ExitPlanPersonInput(
                "Person A",
                62,
                62,
                67,
                new BigDecimal("1500.00"),
                BigDecimal.ZERO,
                new BigDecimal("300.00"),
                false);

        ExitPlanRequest request = new ExitPlanRequest(
                new BigDecimal("250000.00"),
                new BigDecimal("3.00"),
                ExitPlanMode.DEPLETE_TO_AGE,
                85,
                new BigDecimal("50000.00"),
                BigDecimal.ZERO,
                2026,
                person,
                null);

        ExitPlanResult result = calculator.calculate(request);

        assertThat(result.capitalAtTargetAge()).isBetween(new BigDecimal("49995.00"), new BigDecimal("50005.00"));
    }

    @Test
    void familyInsuranceWaivesBridgeKvWhilePartnerWorks() {
        ExitPlanPersonInput personA = new ExitPlanPersonInput(
                "Person A",
                58,
                58,
                67,
                new BigDecimal("1500.00"),
                BigDecimal.ZERO,
                new BigDecimal("450.00"),
                true);
        ExitPlanPersonInput personB = new ExitPlanPersonInput(
                "Person B",
                55,
                59,
                67,
                new BigDecimal("1400.00"),
                new BigDecimal("2500.00"),
                new BigDecimal("450.00"),
                true);

        ExitPlanRequest request = new ExitPlanRequest(
                new BigDecimal("350000.00"),
                new BigDecimal("3.50"),
                ExitPlanMode.FIXED_WITHDRAWAL,
                85,
                BigDecimal.ZERO,
                new BigDecimal("42000.00"),
                2026,
                personA,
                personB);

        ExitPlanResult result = calculator.calculate(request);

        // Age 58..61 for A corresponds to B age 55..58 (B still working -> A is family insured, KV = 0)
        ExitPlanYearRow yearWhenBWorks = result.rows().stream()
                .filter(r -> r.primaryAge() == 59)
                .findFirst()
                .orElseThrow();
        assertThat(yearWhenBWorks.healthInsuranceCost()).isEqualByComparingTo("0.00");
        assertThat(yearWhenBWorks.workingAndPensionIncome()).isEqualByComparingTo("30000.00");

        // At A age 62 (B age 59 = exited), neither works and neither has reached 67 -> both pay bridge KV
        ExitPlanYearRow yearWhenBothRetired = result.rows().stream()
                .filter(r -> r.primaryAge() == 62)
                .findFirst()
                .orElseThrow();
        assertThat(yearWhenBothRetired.healthInsuranceCost()).isEqualByComparingTo("10800.00");
    }

    @Test
    void fixedWithdrawalDetectsExhaustionAgeAndShortfall() {
        ExitPlanPersonInput person = new ExitPlanPersonInput(
                "Person A",
                60,
                60,
                67,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                false);

        ExitPlanRequest request = new ExitPlanRequest(
                new BigDecimal("100000.00"),
                BigDecimal.ZERO,
                ExitPlanMode.FIXED_WITHDRAWAL,
                85,
                BigDecimal.ZERO,
                new BigDecimal("25000.00"),
                2026,
                person,
                null);

        ExitPlanResult result = calculator.calculate(request);

        // 100k / 25k = 4 years covered (ages 60, 61, 62, 63), exhausted at age 63 (endCapital 0) or 64 shortfall
        assertThat(result.exhaustedAtPrimaryAge()).isNotNull().isBetween(63, 64);
        assertThat(result.capitalAtTargetAge()).isEqualByComparingTo("0.00");
        assertThat(result.rows().stream().anyMatch(r -> r.shortfall().signum() > 0)).isTrue();
    }

    @Test
    void preExitYearsCompoundWithoutAssetWithdrawal() {
        ExitPlanPersonInput person = new ExitPlanPersonInput(
                "Person A",
                57,
                60,
                67,
                new BigDecimal("1800.00"),
                BigDecimal.ZERO,
                new BigDecimal("400.00"),
                false);

        ExitPlanRequest request = new ExitPlanRequest(
                new BigDecimal("200000.00"),
                new BigDecimal("5.00"),
                ExitPlanMode.FIXED_WITHDRAWAL,
                85,
                BigDecimal.ZERO,
                new BigDecimal("30000.00"),
                2026,
                person,
                null);

        ExitPlanResult result = calculator.calculate(request);

        ExitPlanYearRow preExitRow = result.rows().get(0);
        assertThat(preExitRow.primaryAge()).isEqualTo(57);
        assertThat(preExitRow.inWithdrawalPhase()).isFalse();
        assertThat(preExitRow.assetWithdrawal()).isEqualByComparingTo("0.00");
        assertThat(preExitRow.endCapital()).isEqualByComparingTo("210000.00");
    }

    @Test
    void preExitSavingsContributeToCapitalUntilExit() {
        ExitPlanPersonInput person = new ExitPlanPersonInput(
                "Person A",
                57,
                60,
                67,
                BigDecimal.ZERO,
                new BigDecimal("1000.00"),
                BigDecimal.ZERO,
                false);

        ExitPlanRequest request = new ExitPlanRequest(
                new BigDecimal("100000.00"),
                BigDecimal.ZERO,
                ExitPlanMode.FIXED_WITHDRAWAL,
                85,
                BigDecimal.ZERO,
                new BigDecimal("20000.00"),
                2026,
                person,
                null);

        ExitPlanResult result = calculator.calculate(request);

        ExitPlanYearRow exitStartRow = result.rows().stream()
                .filter(r -> r.primaryAge() == 60)
                .findFirst()
                .orElseThrow();
        // Ages 57, 58, 59 each save 12,000 -> 100,000 + 36,000 = 136,000 at exit
        assertThat(exitStartRow.startCapital()).isEqualByComparingTo("136000.00");
        assertThat(exitStartRow.workingAndPensionIncome()).isEqualByComparingTo("0.00");
    }
}
