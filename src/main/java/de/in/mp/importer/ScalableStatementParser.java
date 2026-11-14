package de.in.mp.importer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import de.in.mp.finance.AccountType;

/**
 * Parser for monthly Scalable Capital Bank GmbH account statements
 * ({@code Kontoauszug}).
 * <p>
 * Extracts the closing balance for the period end date from
 * {@code Kontostand am <closingDate>}, the clearing account IBAN
 * ({@code Verrechnungskonto}) as stable import key, and monthly inflows and
 * outflows by summing signed booking lines.
 */
@Component
public class ScalableStatementParser implements BankStatementParser {

    static final String BANK = "Scalable Capital";
    static final String DEFAULT_LABEL = "Verrechnungskonto";

    private static final String BANK_MARKER = "SCALABLE CAPITAL";
    private static final String STATEMENT_MARKER = "KONTOAUSZUG";
    private static final Pattern PERIOD = Pattern.compile(
            "Zeitraum\\s+(" + StatementText.DATE + ")\\s*-\\s*(" + StatementText.DATE + ")");
    private static final Pattern IBAN = Pattern.compile(
            "Verrechnungskonto\\s+(DE\\d{2}(?:\\s?\\d{4}){4}\\s?\\d{2})");
    private static final Pattern PAGE_LINE = Pattern.compile("^Seite\\s+\\d+\\s*/\\s*\\d+$");
    private static final Pattern POSTAL_CODE_LINE = Pattern.compile("^\\d{5}\\s+\\S.*");
    private static final Pattern BOOKING_LINE = Pattern.compile(
            "^" + StatementText.DATE + "\\s+" + StatementText.DATE
                    + "\\s+.+?\\s+(" + StatementText.AMOUNT + ")\\s*(?:EUR|€)$");

    @Override
    public boolean supports(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String upper = StatementText.normalize(text).toUpperCase(Locale.ROOT);
        return upper.contains(BANK_MARKER)
                && upper.contains(STATEMENT_MARKER)
                && upper.contains("VERRECHNUNGSKONTO");
    }

    @Override
    public List<StatementEntry> parse(String rawText) {
        String text = StatementText.normalize(rawText);
        Matcher period = PERIOD.matcher(text);
        if (!period.find()) {
            throw new IllegalArgumentException("Scalable statement period not found");
        }
        String closingDateRaw = period.group(2);
        LocalDate closingDate = StatementText.parseDate(closingDateRaw);

        BigDecimal closingBalance = StatementText.findAmount(
                text, "Kontostand\\s+am\\s+" + Pattern.quote(closingDateRaw));
        if (closingBalance == null) {
            closingBalance = StatementText.findAmount(text, "Kontostand");
        }
        if (closingBalance == null) {
            throw new IllegalArgumentException("Scalable closing balance not found");
        }

        Matcher ibanMatcher = IBAN.matcher(text);
        String iban = ibanMatcher.find() ? StatementText.stripSpaces(ibanMatcher.group(1)) : null;

        List<String> lines = StatementText.lines(text).stream()
                .filter(line -> !line.isEmpty())
                .toList();
        String holder = extractHolder(lines);
        BigDecimal[] flows = sumBookingFlows(lines);

        String importKey = iban != null
                ? "IBAN|" + iban
                : BANK + "|" + holder.toLowerCase(Locale.ROOT) + "|" + DEFAULT_LABEL.toLowerCase(Locale.ROOT);

        return List.of(new StatementEntry(
                BANK,
                DEFAULT_LABEL,
                AccountType.SAVINGS,
                iban,
                null,
                holder,
                closingDate,
                closingBalance,
                flows[0],
                flows[1],
                importKey));
    }

    private static String extractHolder(List<String> lines) {
        // Check right-hand header column below "Seite X / Y" (OCR/column order)
        for (int i = 0; i < lines.size() - 1; i++) {
            if (PAGE_LINE.matcher(lines.get(i)).matches()) {
                String candidate = lines.get(i + 1);
                if (isHolderCandidate(candidate)) {
                    return candidate;
                }
            }
        }
        // Fallback: line two rows above "<PLZ> <Ort>" when address block starts with name
        for (int i = 2; i < lines.size(); i++) {
            if (POSTAL_CODE_LINE.matcher(lines.get(i)).matches()) {
                String candidate = lines.get(i - 2);
                if (isHolderCandidate(candidate)) {
                    return candidate;
                }
            }
        }
        return "";
    }

    private static boolean isHolderCandidate(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String upper = line.toUpperCase(Locale.ROOT);
        return !upper.startsWith(BANK_MARKER)
                && !upper.startsWith(STATEMENT_MARKER)
                && !upper.startsWith("DATUM ")
                && !upper.startsWith("VERTEILUNG ");
    }

    private static BigDecimal[] sumBookingFlows(List<String> lines) {
        BigDecimal inflow = BigDecimal.ZERO;
        BigDecimal outflow = BigDecimal.ZERO;
        for (String line : lines) {
            Matcher matcher = BOOKING_LINE.matcher(line.replaceAll("\\s+", " ").trim());
            if (!matcher.matches()) {
                continue;
            }
            BigDecimal amount = StatementText.parseAmount(matcher.group(1));
            if (amount.signum() > 0) {
                inflow = inflow.add(amount);
            } else if (amount.signum() < 0) {
                outflow = outflow.add(amount.abs());
            }
        }
        return new BigDecimal[] { inflow, outflow };
    }
}
