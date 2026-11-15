package de.in.mp.finance.exitplan;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

/**
 * Deterministic annual household exit/runway simulator supporting asymmetric
 * partner retirement ages, bridge health-insurance options (including family
 * insurance while a partner is still working), capital preservation and target-age
 * capital depletion via bisection.
 */
@Service
public class ExitPlanCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TWELVE = new BigDecimal("12");
    private static final BigDecimal TWO = new BigDecimal("2");
    private static final BigDecimal BISECTION_UPPER_PADDING = new BigDecimal("1000000");
    private static final int MONEY_SCALE = 2;
    private static final int RATE_SCALE = 6;
    private static final int BISECTION_ITERATIONS = 64;
    private static final int MIN_HORIZON_PRIMARY_AGE = 95;

    public ExitPlanResult calculate(ExitPlanRequest request) {
        Objects.requireNonNull(request, "ExitPlanRequest must not be null");
        ExitPlanPersonInput primary = Objects.requireNonNull(request.primaryPerson(),
                "Primary person input must not be null");
        ExitPlanPersonInput secondary = request.secondaryPerson();
        ExitPlanMode mode = request.mode() == null ? ExitPlanMode.DEPLETE_TO_AGE : request.mode();

        BigDecimal startCapital = nonNegative(request.startingCapital());
        BigDecimal annualRate = nonNegative(request.annualReturnPercent())
                .divide(HUNDRED, RATE_SCALE, RoundingMode.HALF_UP);
        int targetAge = Math.max(primary.currentAge(), request.targetAge());
        BigDecimal targetResidual = nonNegative(request.targetResidualCapital());
        int horizonAge = Math.max(targetAge, MIN_HORIZON_PRIMARY_AGE);
        int totalYears = Math.max(1, horizonAge - primary.currentAge() + 1);
        int firstExitOffset = resolveFirstExitOffset(primary, secondary, totalYears);

        return switch (mode) {
            case CAPITAL_PRESERVATION -> simulateCapitalPreservation(
                    request, startCapital, annualRate, targetAge, totalYears, firstExitOffset);
            case DEPLETE_TO_AGE -> simulateDepleteToAge(
                    request, startCapital, annualRate, targetAge, targetResidual, totalYears, firstExitOffset);
            case FIXED_WITHDRAWAL -> buildResult(
                    mode,
                    nonNegative(request.desiredAnnualBudget()),
                    targetAge,
                    primary,
                    secondary,
                    simulateFixedBudget(request, startCapital, annualRate,
                            nonNegative(request.desiredAnnualBudget()), totalYears, firstExitOffset));
        };
    }

    private ExitPlanResult simulateCapitalPreservation(
            ExitPlanRequest request,
            BigDecimal startCapital,
            BigDecimal annualRate,
            int targetAge,
            int totalYears,
            int firstExitOffset) {
        ExitPlanPersonInput primary = request.primaryPerson();
        ExitPlanPersonInput secondary = request.secondaryPerson();
        List<ExitPlanYearRow> rows = new ArrayList<>(totalYears);
        BigDecimal capital = startCapital;
        BigDecimal headlineAnnualBudget = null;

        for (int offset = 0; offset < totalYears; offset++) {
            int calendarYear = request.baseCalendarYear() + offset;
            int ageA = primary.currentAge() + offset;
            Integer ageB = secondary == null ? null : secondary.currentAge() + offset;
            boolean inWithdrawal = offset >= firstExitOffset;

            BigDecimal investmentReturn = capital.multiply(annualRate).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal inflow = computeAnnualInflow(primary, secondary, ageA, ageB);
            BigDecimal kvCost = computeBridgeHealthInsurance(primary, secondary, ageA, ageB, inWithdrawal);
            BigDecimal assetWithdrawal = inWithdrawal ? investmentReturn : BigDecimal.ZERO.setScale(MONEY_SCALE);
            BigDecimal availableBudget = inWithdrawal
                    ? assetWithdrawal.add(inflow).subtract(kvCost).max(BigDecimal.ZERO)
                    : BigDecimal.ZERO.setScale(MONEY_SCALE);
            BigDecimal endCapital = inWithdrawal
                    ? capital.add(investmentReturn).subtract(assetWithdrawal).setScale(MONEY_SCALE, RoundingMode.HALF_UP)
                    : capital.add(investmentReturn).add(inflow).setScale(MONEY_SCALE, RoundingMode.HALF_UP);

            if (inWithdrawal && headlineAnnualBudget == null) {
                headlineAnnualBudget = availableBudget;
            }
            rows.add(new ExitPlanYearRow(
                    offset, calendarYear, ageA, ageB, inWithdrawal,
                    capital, inflow, kvCost, availableBudget, assetWithdrawal,
                    investmentReturn, endCapital, BigDecimal.ZERO.setScale(MONEY_SCALE)));
            capital = endCapital;
        }

        BigDecimal effectiveBudget = headlineAnnualBudget != null
                ? headlineAnnualBudget
                : BigDecimal.ZERO.setScale(MONEY_SCALE);
        return buildResult(ExitPlanMode.CAPITAL_PRESERVATION, effectiveBudget, targetAge, primary, secondary, rows);
    }

    private ExitPlanResult simulateDepleteToAge(
            ExitPlanRequest request,
            BigDecimal startCapital,
            BigDecimal annualRate,
            int targetAge,
            BigDecimal targetResidual,
            int totalYears,
            int firstExitOffset) {
        ExitPlanPersonInput primary = request.primaryPerson();
        ExitPlanPersonInput secondary = request.secondaryPerson();
        int targetIndex = Math.min(totalYears - 1, Math.max(0, targetAge - primary.currentAge()));

        BigDecimal low = BigDecimal.ZERO;
        BigDecimal high = startCapital.multiply(TWO).add(BISECTION_UPPER_PADDING);
        for (int i = 0; i < BISECTION_ITERATIONS; i++) {
            BigDecimal mid = low.add(high).divide(TWO, RATE_SCALE, RoundingMode.HALF_UP);
            List<ExitPlanYearRow> trial = simulateFixedBudget(
                    request, startCapital, annualRate, mid, targetIndex + 1, firstExitOffset);
            boolean coveredWithoutShortfall = trial.stream().noneMatch(r -> r.shortfall().signum() > 0);
            BigDecimal endAtTarget = trial.get(targetIndex).endCapital();
            if (coveredWithoutShortfall && endAtTarget.compareTo(targetResidual) >= 0) {
                low = mid;
            } else {
                high = mid;
            }
        }

        BigDecimal solvedAnnualBudget = low.setScale(MONEY_SCALE, RoundingMode.FLOOR);
        List<ExitPlanYearRow> finalRows = simulateFixedBudget(
                request, startCapital, annualRate, solvedAnnualBudget, totalYears, firstExitOffset);
        return buildResult(ExitPlanMode.DEPLETE_TO_AGE, solvedAnnualBudget, targetAge, primary, secondary, finalRows);
    }

    private List<ExitPlanYearRow> simulateFixedBudget(
            ExitPlanRequest request,
            BigDecimal startCapital,
            BigDecimal annualRate,
            BigDecimal annualLivingBudget,
            int totalYears,
            int firstExitOffset) {
        ExitPlanPersonInput primary = request.primaryPerson();
        ExitPlanPersonInput secondary = request.secondaryPerson();
        List<ExitPlanYearRow> rows = new ArrayList<>(totalYears);
        BigDecimal capital = startCapital;

        for (int offset = 0; offset < totalYears; offset++) {
            int calendarYear = request.baseCalendarYear() + offset;
            int ageA = primary.currentAge() + offset;
            Integer ageB = secondary == null ? null : secondary.currentAge() + offset;
            boolean inWithdrawal = offset >= firstExitOffset;

            BigDecimal investmentReturn = capital.multiply(annualRate).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal inflow = computeAnnualInflow(primary, secondary, ageA, ageB);
            BigDecimal kvCost = computeBridgeHealthInsurance(primary, secondary, ageA, ageB, inWithdrawal);
            BigDecimal budget = inWithdrawal
                    ? annualLivingBudget.setScale(MONEY_SCALE, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO.setScale(MONEY_SCALE);

            BigDecimal maxAvailable = capital.add(investmentReturn);
            BigDecimal actualWithdrawal;
            BigDecimal shortfall;
            BigDecimal endCapital;
            if (!inWithdrawal) {
                actualWithdrawal = BigDecimal.ZERO.setScale(MONEY_SCALE);
                shortfall = BigDecimal.ZERO.setScale(MONEY_SCALE);
                endCapital = maxAvailable.add(inflow).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            } else {
                BigDecimal netNeeded = budget.add(kvCost).subtract(inflow);
                if (netNeeded.signum() <= 0) {
                    actualWithdrawal = BigDecimal.ZERO.setScale(MONEY_SCALE);
                    shortfall = BigDecimal.ZERO.setScale(MONEY_SCALE);
                    endCapital = maxAvailable.add(netNeeded.negate()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                } else {
                    actualWithdrawal = netNeeded.min(maxAvailable).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                    shortfall = netNeeded.subtract(actualWithdrawal)
                            .max(BigDecimal.ZERO)
                            .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                    endCapital = maxAvailable.subtract(actualWithdrawal)
                            .max(BigDecimal.ZERO)
                            .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                }
            }

            rows.add(new ExitPlanYearRow(
                    offset, calendarYear, ageA, ageB, inWithdrawal,
                    capital, inflow, kvCost, budget, actualWithdrawal,
                    investmentReturn, endCapital, shortfall));
            capital = endCapital;
        }
        return rows;
    }

    private ExitPlanResult buildResult(
            ExitPlanMode mode,
            BigDecimal annualBudget,
            int targetAge,
            ExitPlanPersonInput primary,
            ExitPlanPersonInput secondary,
            List<ExitPlanYearRow> rows) {
        BigDecimal monthlyBudget = annualBudget.divide(TWELVE, MONEY_SCALE, RoundingMode.HALF_UP);

        BigDecimal capitalAtFirstPension = rows.stream()
                .filter(row -> isPensionActive(primary, row.primaryAge())
                        || (secondary != null && row.secondaryAge() != null
                                && isPensionActive(secondary, row.secondaryAge())))
                .findFirst()
                .map(ExitPlanYearRow::startCapital)
                .orElse(null);

        BigDecimal capitalAtTargetAge = rows.stream()
                .filter(row -> row.primaryAge() == targetAge)
                .findFirst()
                .map(ExitPlanYearRow::endCapital)
                .orElseGet(() -> rows.isEmpty() ? BigDecimal.ZERO : rows.get(rows.size() - 1).endCapital());

        Integer exhaustedAtAge = rows.stream()
                .filter(row -> row.inWithdrawalPhase()
                        && (row.endCapital().signum() == 0 || row.shortfall().signum() > 0))
                .findFirst()
                .map(ExitPlanYearRow::primaryAge)
                .orElse(null);

        return new ExitPlanResult(
                mode,
                annualBudget.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                monthlyBudget,
                capitalAtFirstPension,
                capitalAtTargetAge,
                exhaustedAtAge,
                rows);
    }

    private static int resolveFirstExitOffset(
            ExitPlanPersonInput primary,
            ExitPlanPersonInput secondary,
            int totalYears) {
        for (int offset = 0; offset < totalYears; offset++) {
            int ageA = primary.currentAge() + offset;
            if (ageA >= primary.exitAge()) {
                return offset;
            }
            if (secondary != null && (secondary.currentAge() + offset) >= secondary.exitAge()) {
                return offset;
            }
        }
        return totalYears;
    }

    private static BigDecimal computeAnnualInflow(
            ExitPlanPersonInput primary,
            ExitPlanPersonInput secondary,
            int ageA,
            Integer ageB) {
        BigDecimal total = BigDecimal.ZERO;
        total = total.add(personAnnualInflow(primary, ageA));
        if (secondary != null && ageB != null) {
            total = total.add(personAnnualInflow(secondary, ageB));
        }
        return total.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal personAnnualInflow(ExitPlanPersonInput person, int age) {
        if (isPensionActive(person, age)) {
            return nonNegative(person.monthlyPension()).multiply(TWELVE);
        }
        if (age < person.exitAge()) {
            return nonNegative(person.monthlySavings()).multiply(TWELVE);
        }
        return BigDecimal.ZERO;
    }

    private static BigDecimal computeBridgeHealthInsurance(
            ExitPlanPersonInput primary,
            ExitPlanPersonInput secondary,
            int ageA,
            Integer ageB,
            boolean inWithdrawal) {
        if (!inWithdrawal) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE);
        }
        boolean primaryWorking = ageA < primary.exitAge();
        boolean secondaryWorking = secondary != null && ageB != null && ageB < secondary.exitAge();

        BigDecimal total = BigDecimal.ZERO;
        if (isInBridge(primary, ageA) && !(primary.familyInsuredWhilePartnerWorks() && secondaryWorking)) {
            total = total.add(nonNegative(primary.monthlyBridgeHealthInsurance()).multiply(TWELVE));
        }
        if (secondary != null && ageB != null && isInBridge(secondary, ageB)
                && !(secondary.familyInsuredWhilePartnerWorks() && primaryWorking)) {
            total = total.add(nonNegative(secondary.monthlyBridgeHealthInsurance()).multiply(TWELVE));
        }
        return total.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static boolean isInBridge(ExitPlanPersonInput person, int age) {
        return age >= person.exitAge() && age < person.pensionAge();
    }

    private static boolean isPensionActive(ExitPlanPersonInput person, int age) {
        return age >= person.pensionAge();
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
