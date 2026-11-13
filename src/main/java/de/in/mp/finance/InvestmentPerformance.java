package de.in.mp.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Optional;

import de.in.mp.i18n.MoneyFormatter;

/**
 * Derived investment performance metrics from a depot's current balance and its
 * optional invested capital (cost basis).
 */
public record InvestmentPerformance(BigDecimal gain, BigDecimal returnPercent) {

    private static final int MONEY_SCALE = 2;
    private static final int PERCENT_SCALE = 1;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public static Optional<InvestmentPerformance> of(BigDecimal balance, BigDecimal investedCapital) {
        if (balance == null || investedCapital == null) {
            return Optional.empty();
        }
        BigDecimal gain = balance.subtract(investedCapital).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal percent = investedCapital.signum() > 0
                ? gain.multiply(HUNDRED).divide(investedCapital, PERCENT_SCALE, RoundingMode.HALF_UP)
                : null;
        return Optional.of(new InvestmentPerformance(gain, percent));
    }

    public boolean isPositive() {
        return gain.signum() > 0;
    }

    public boolean isNegative() {
        return gain.signum() < 0;
    }

    public String format(Locale locale) {
        Locale effectiveLocale = locale == null ? Locale.GERMANY : locale;
        String gainText = (gain.signum() > 0 ? "+" : "") + MoneyFormatter.format(gain, effectiveLocale);
        if (returnPercent == null) {
            return gainText;
        }
        NumberFormat percentFormat = NumberFormat.getNumberInstance(effectiveLocale);
        percentFormat.setMinimumFractionDigits(PERCENT_SCALE);
        percentFormat.setMaximumFractionDigits(PERCENT_SCALE);
        String percentPrefix = returnPercent.signum() > 0 ? "+" : "";
        return gainText + " (" + percentPrefix + percentFormat.format(returnPercent) + " %)";
    }
}
