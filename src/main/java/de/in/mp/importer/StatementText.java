package de.in.mp.importer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text helpers shared by the statement parsers (German number and date formats).
 */
final class StatementText {

    /** German amount, e.g. {@code -1.234,56} or {@code + 12,00}. */
    static final String AMOUNT = "[+-]?\\s?\\d{1,3}(?:\\.?\\d{3})*,\\d{2}";

    static final String DATE = "\\d{2}\\.\\d{2}\\.\\d{4}";

    private static final DateTimeFormatter GERMAN_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private StatementText() {
    }

    /**
     * Unifies line breaks and exotic whitespace produced by PDF text extraction.
     */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\u00A0', ' ')
                .replace('\u2212', '-');
    }

    static List<String> lines(String text) {
        return Arrays.stream(text.split("\n"))
                .map(String::trim)
                .toList();
    }

    static BigDecimal parseAmount(String raw) {
        String cleaned = raw.replace(" ", "").replace(".", "").replace(',', '.');
        return new BigDecimal(cleaned);
    }

    static LocalDate parseDate(String raw) {
        return LocalDate.parse(raw, GERMAN_DATE);
    }

    static BigDecimal findAmount(String text, String prefixRegex) {
        Matcher matcher = Pattern.compile(prefixRegex + "\\s*(" + AMOUNT + ")").matcher(text);
        return matcher.find() ? parseAmount(matcher.group(1)) : null;
    }

    static String stripSpaces(String value) {
        return value == null ? null : value.replaceAll("\\s", "");
    }
}
