package de.in.mp.importer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import de.in.mp.finance.AccountType;

/**
 * Parser for Chase Bank (J.P. Morgan SE) monthly account statements.
 * <p>
 * Recognizes headers such as {@code Kontoauszug – Chase Tagesgeld}, periods
 * formatted with German month names ({@code dd. MMMM yyyy}), and restricts the
 * IBAN and summary lookup to the statement header before
 * {@code Transaktionsübersicht} so counterparty IBANs in bookings are ignored.
 */
@Component
public class ChaseStatementParser implements BankStatementParser {

    static final String BANK = "Chase";
    private static final String DEFAULT_LABEL = "Chase Tagesgeld";

    private static final String CHASE_MARKER = "CHASE";
    private static final String LEGAL_ENTITY_MARKER = "J.P. MORGAN SE";
    private static final String BIC_MARKER = "CHASDEF2";
    private static final String STATEMENT_MARKER = "KONTOAUSZUG";
    private static final String TRANSACTIONS_MARKER = "Transaktionsübersicht";

    private static final String GERMAN_LONG_DATE = "\\d{1,2}\\.\\s+[\\p{L}]+\\s+\\d{4}";
    private static final DateTimeFormatter LONG_DATE_FORMAT =
            DateTimeFormatter.ofPattern("d. MMMM yyyy", Locale.GERMAN);

    private static final Pattern PERIOD = Pattern.compile(
            "Zeitraum:?\\s+(" + GERMAN_LONG_DATE + ")\\s*[–-]\\s*(" + GERMAN_LONG_DATE + ")");
    private static final Pattern PRODUCT_TITLE = Pattern.compile(
            "Kontoauszug\\s*[–-]\\s*(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADER_IBAN = Pattern.compile(
            "IBAN:?\\s*(DE\\d{2}(?:\\s?\\d{4}){4}\\s?\\d{2})");
    private static final Pattern POSTAL_CODE_LINE = Pattern.compile("^\\d{5}\\s+[^.]+$");
    private static final List<String> SAVINGS_MARKERS = List.of("tagesgeld", "spar");

    @Override
    public boolean supports(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String upper = StatementText.normalize(text).toUpperCase(Locale.ROOT);
        boolean hasIssuer = upper.contains(LEGAL_ENTITY_MARKER) || upper.contains(BIC_MARKER);
        return upper.contains(CHASE_MARKER) && upper.contains(STATEMENT_MARKER) && hasIssuer;
    }

    @Override
    public List<StatementEntry> parse(String rawText) {
        String text = StatementText.normalize(rawText);
        Matcher period = PERIOD.matcher(text);
        if (!period.find()) {
            throw new IllegalArgumentException("Chase statement period not found");
        }
        LocalDate closingDate = parseGermanLongDate(period.group(2));

        String headerAndSummary = extractHeaderAndSummary(text);
        BigDecimal balance = StatementText.findAmount(headerAndSummary, "Endsaldo:?");
        if (balance == null) {
            throw new IllegalArgumentException("Chase closing balance not found");
        }
        BigDecimal inflow = abs(StatementText.findAmount(headerAndSummary, "Kontogutschriften:?"));
        BigDecimal outflow = abs(StatementText.findAmount(headerAndSummary, "Kontobelastungen:?"));

        Matcher ibanMatcher = HEADER_IBAN.matcher(headerAndSummary);
        String iban = ibanMatcher.find() ? StatementText.stripSpaces(ibanMatcher.group(1)) : null;

        List<String> headerLines = StatementText.lines(headerAndSummary).stream()
                .filter(line -> !line.isEmpty())
                .toList();
        String product = extractProduct(headerLines);
        String holder = extractHolder(headerLines);
        String importKey = iban != null
                ? "IBAN|" + iban
                : BANK + "|" + holder.toLowerCase(Locale.ROOT) + "|" + product.toLowerCase(Locale.ROOT);

        return List.of(new StatementEntry(
                BANK,
                product,
                suggestType(product),
                iban,
                null,
                holder,
                closingDate,
                balance,
                inflow,
                outflow,
                importKey));
    }

    private static String extractHeaderAndSummary(String text) {
        int txStart = text.indexOf(TRANSACTIONS_MARKER);
        return txStart > 0 ? text.substring(0, txStart) : text;
    }

    private static LocalDate parseGermanLongDate(String rawDate) {
        String collapsed = rawDate.replaceAll("\\s+", " ").trim();
        return LocalDate.parse(collapsed, LONG_DATE_FORMAT);
    }

    private static String extractProduct(List<String> lines) {
        for (String line : lines) {
            Matcher matcher = PRODUCT_TITLE.matcher(line);
            if (matcher.matches()) {
                return matcher.group(1).trim();
            }
        }
        return DEFAULT_LABEL;
    }

    private static String extractHolder(List<String> lines) {
        for (int i = 2; i < lines.size(); i++) {
            if (!POSTAL_CODE_LINE.matcher(lines.get(i)).matches()) {
                continue;
            }
            String street = lines.get(i - 1).toUpperCase(Locale.ROOT);
            String candidate = lines.get(i - 2);
            String candidateUpper = candidate.toUpperCase(Locale.ROOT);
            if (!street.contains("POSTFACH")
                    && !candidateUpper.contains(LEGAL_ENTITY_MARKER)
                    && !candidateUpper.startsWith("DU KANNST")) {
                return candidate;
            }
        }
        return "";
    }

    private static AccountType suggestType(String product) {
        String lower = product.toLowerCase(Locale.ROOT);
        return SAVINGS_MARKERS.stream().anyMatch(lower::contains) ? AccountType.SAVINGS : AccountType.CHECKING;
    }

    private static BigDecimal abs(BigDecimal value) {
        return value == null ? null : value.abs();
    }
}
