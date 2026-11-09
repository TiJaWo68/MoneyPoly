package de.in.mp.i18n;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;

/**
 * Formats monetary amounts as Euro while respecting the session locale for
 * grouping and decimal separators.
 * <p>
 * Language-only locales (e.g. {@link Locale#GERMAN}) cannot resolve a currency,
 * which makes {@link NumberFormat} fall back to the generic currency sign
 * (¤). The locale is therefore normalized to a country-bearing variant before
 * formatting.
 */
public final class MoneyFormatter {

    private static final Currency EURO = Currency.getInstance("EUR");

    private MoneyFormatter() {
    }

    public static String format(BigDecimal amount, Locale locale) {
        NumberFormat format = NumberFormat.getCurrencyInstance(normalize(locale));
        format.setCurrency(EURO);
        return format.format(amount);
    }

    private static Locale normalize(Locale locale) {
        if (locale == null) {
            return Locale.GERMANY;
        }
        if (!locale.getCountry().isEmpty()) {
            return locale;
        }
        return switch (locale.getLanguage().toLowerCase(Locale.ROOT)) {
            case "de" -> Locale.GERMANY;
            case "en" -> Locale.US;
            default -> locale;
        };
    }
}
