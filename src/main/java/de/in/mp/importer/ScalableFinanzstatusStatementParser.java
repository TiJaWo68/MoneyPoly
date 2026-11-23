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
 * Parser for Scalable Capital Bank GmbH {@code Finanzstatus} overview PDFs
 * (both {@code Tagesgeld} cash statements and {@code Depot} portfolio summaries)
 * for direct import via "Kontoauszug (PDF) importieren".
 */
@Component
public class ScalableFinanzstatusStatementParser implements BankStatementParser {

    static final String BANK = "Scalable Capital";
    static final String TAGESGELD_LABEL = "Tagesgeld";
    static final String DEPOT_LABEL = "Depot";

    private static final String BANK_MARKER = "SCALABLE CAPITAL";
    private static final String DOC_MARKER = "FINANZSTATUS";
    private static final Pattern STATUS_DATE = Pattern.compile(
            "Finanzstatus\\s+per\\s+(" + StatementText.DATE + ")");
    private static final Pattern FALLBACK_DATE = Pattern.compile(
            "Datum\\s+(" + StatementText.DATE + ")");
    private static final Pattern TAGESGELD_BALANCE = Pattern.compile(
            "Tagesgeld\\s+(" + StatementText.AMOUNT + ")\\s*(?:EUR|€)");
    private static final Pattern POSTAL_CODE_LINE = Pattern.compile("^\\d{5}\\s+\\S.*");

    @Override
    public boolean supports(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return false;
        }
        String upper = StatementText.normalize(rawText).toUpperCase(Locale.ROOT);
        return upper.contains(BANK_MARKER)
                && upper.contains(DOC_MARKER)
                && (upper.contains("TAGESGELD") || upper.contains("POSITIONSWERT"));
    }

    @Override
    public List<StatementEntry> parse(String rawText) {
        if (!supports(rawText)) {
            throw new IllegalArgumentException("Not a supported Scalable Capital Finanzstatus PDF");
        }
        String text = StatementText.normalize(rawText);
        String upper = text.toUpperCase(Locale.ROOT);

        LocalDate closingDate = extractDate(text);
        boolean isTagesgeld = upper.contains("TAGESGELD")
                && !upper.contains("STÜCKZAHL")
                && !upper.contains("STUECKZAHL");

        String label = isTagesgeld ? TAGESGELD_LABEL : DEPOT_LABEL;
        AccountType accountType = isTagesgeld ? AccountType.SAVINGS : AccountType.INVESTMENT;
        BigDecimal balance = isTagesgeld ? extractTagesgeldBalance(text) : extractDepotBalance(text);

        List<String> lines = StatementText.lines(text).stream()
                .filter(line -> !line.isEmpty())
                .toList();
        String holder = extractHolder(lines);
        String importKey = BANK + "|" + holder.toLowerCase(Locale.ROOT) + "|"
                + label.toLowerCase(Locale.ROOT);

        return List.of(new StatementEntry(
                BANK,
                label,
                accountType,
                null,
                null,
                holder,
                closingDate,
                balance,
                null,
                null,
                importKey));
    }

    private static LocalDate extractDate(String text) {
        Matcher statusMatcher = STATUS_DATE.matcher(text);
        if (statusMatcher.find()) {
            return StatementText.parseDate(statusMatcher.group(1));
        }
        Matcher fallbackMatcher = FALLBACK_DATE.matcher(text);
        if (fallbackMatcher.find()) {
            return StatementText.parseDate(fallbackMatcher.group(1));
        }
        throw new IllegalArgumentException("Scalable Finanzstatus date not found");
    }

    private static BigDecimal extractTagesgeldBalance(String text) {
        Matcher matcher = TAGESGELD_BALANCE.matcher(text);
        if (matcher.find()) {
            return StatementText.parseAmount(matcher.group(1));
        }
        BigDecimal fallback = StatementText.findAmount(text, "Gesamt");
        if (fallback != null) {
            return fallback;
        }
        throw new IllegalArgumentException("Scalable Tagesgeld balance not found");
    }

    private static BigDecimal extractDepotBalance(String text) {
        BigDecimal total = StatementText.findAmount(text, "Gesamt");
        if (total != null) {
            return total;
        }
        throw new IllegalArgumentException("Scalable Depot total balance not found");
    }

    private static String extractHolder(List<String> lines) {
        for (int i = 2; i < lines.size(); i++) {
            if (POSTAL_CODE_LINE.matcher(lines.get(i)).matches()) {
                String candidate = lines.get(i - 2);
                if (!candidate.toUpperCase(Locale.ROOT).contains(BANK_MARKER)) {
                    return candidate;
                }
            }
        }
        for (int i = 0; i < lines.size() - 1; i++) {
            if (lines.get(i).toUpperCase(Locale.ROOT).contains(BANK_MARKER)) {
                return lines.get(i + 1);
            }
        }
        return "";
    }
}
