package de.in.mp.importer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import de.in.mp.finance.AccountType;

/**
 * Parser for monthly C24 Bank statements ("Kontoauszug MM/YYYY").
 * <p>
 * Layout: holder and address block, product lines, optional IBAN, period,
 * "Kontostand", transactions and a monthly "Zusammenfassung" with
 * Startsaldo / Kontobelastungen / Kontogutschriften / Endsaldo. Quarterly
 * statements add a second summary (Rechnungsabschluss) further down, so
 * always the first summary is used. Savings pockets carry no IBAN; they are
 * identified by holder and product name instead.
 */
@Component
public class C24StatementParser implements BankStatementParser {

    static final String BANK = "C24";

    private static final String BANK_MARKER = "C24 Bank GmbH";
    private static final Pattern PERIOD = Pattern.compile(
            "Kontoauszug\\s+\\d{2}/\\d{4}.{0,200}?(" + StatementText.DATE + ")\\s*-\\s*("
                    + StatementText.DATE + ")",
            Pattern.DOTALL);
    private static final Pattern HEADER_IBAN = Pattern.compile("IBAN:\\s*(DE\\d{2}(?:\\s?\\d{4}){4}\\s?\\d{2})");
    private static final Pattern POSTAL_CODE_LINE = Pattern.compile("^\\d{5}\\s+\\S.*");
    private static final Pattern COMBINED_ADDRESS_LINE = Pattern.compile("^.+?,\\s*\\d{5}\\s+\\S.*");
    private static final List<String> SAVINGS_MARKERS = List.of("tagesgeld", "pocket", "spar");

    @Override
    public boolean supports(String text) {
        return text != null && text.contains(BANK_MARKER);
    }

    @Override
    public List<StatementEntry> parse(String rawText) {
        String text = StatementText.normalize(rawText);

        Matcher period = PERIOD.matcher(text);
        if (!period.find()) {
            throw new IllegalArgumentException("C24 statement period not found");
        }
        LocalDate closingDate = StatementText.parseDate(period.group(2));
        String header = text.substring(0, period.start());

        BigDecimal balance = StatementText.findAmount(text, "Kontostand");
        if (balance == null) {
            balance = StatementText.findAmount(text, "Endsaldo");
        }
        if (balance == null) {
            throw new IllegalArgumentException("C24 closing balance not found");
        }
        BigDecimal outflow = abs(StatementText.findAmount(text, "Kontobelastungen"));
        BigDecimal inflow = abs(StatementText.findAmount(text, "Kontogutschriften"));

        Matcher ibanMatcher = HEADER_IBAN.matcher(header);
        String iban = ibanMatcher.find() ? StatementText.stripSpaces(ibanMatcher.group(1)) : null;

        List<String> headerLines = StatementText.lines(header).stream().filter(line -> !line.isEmpty()).toList();
        String holder = extractHolder(headerLines, text);
        String product = extractProduct(headerLines);

        String importKey = iban != null
                ? "IBAN|" + iban
                : BANK + "|" + holder.toLowerCase(Locale.ROOT) + "|" + product.toLowerCase(Locale.ROOT);

        return List.of(new StatementEntry(BANK, product, suggestType(product), iban, null, holder,
                closingDate, balance, inflow, outflow, importKey));
    }

    static String extractHolder(List<String> headerLines, String fullText) {
        if (headerLines.isEmpty()) {
            return "";
        }
        String first = headerLines.get(0);
        if (!isProductOrFooterLine(first)) {
            return first;
        }
        // In some C24 layouts, product/footer lines precede the address block; look up
        // the name line positioned before the customer's address anywhere in the text.
        List<String> allLines = StatementText.lines(fullText).stream().filter(line -> !line.isEmpty()).toList();
        for (int i = 1; i < allLines.size(); i++) {
            String line = allLines.get(i);
            if (isBankAddressLine(line)) {
                continue;
            }
            if (COMBINED_ADDRESS_LINE.matcher(line).matches() && !isProductOrFooterLine(allLines.get(i - 1))) {
                return allLines.get(i - 1);
            }
            if (i >= 2 && POSTAL_CODE_LINE.matcher(line).matches() && !isProductOrFooterLine(allLines.get(i - 2))) {
                return allLines.get(i - 2);
            }
        }
        return first;
    }

    private static boolean isBankAddressLine(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("c24 bank") || lower.contains("speicherstraße") || lower.contains("60327 frankfurt");
    }

    private static boolean isProductOrFooterLine(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("tagesgeld") || lower.contains("smartkonto")
                || lower.contains("pocket") || lower.contains("c24 bank")
                || lower.contains("seite ");
    }

    /**
     * Product lines sit between the postal code line of the address block and
     * the IBAN/BIC lines (e.g. "C24 Smartkonto" + "Girokonto").
     */
    static String extractProduct(List<String> headerLines) {
        List<String> productLines = new ArrayList<>();
        boolean afterAddress = false;
        for (String line : headerLines) {
            if (!afterAddress) {
                afterAddress = POSTAL_CODE_LINE.matcher(line).matches();
                continue;
            }
            if (line.startsWith("IBAN") || line.startsWith("BIC")) {
                break;
            }
            productLines.add(line);
        }
        String joined = String.join(" ", productLines).trim();
        if (!joined.isEmpty()) {
            return joined;
        }
        return headerLines.stream()
                .filter(l -> {
                    String lower = l.toLowerCase(Locale.ROOT);
                    return lower.contains("tagesgeld") || lower.contains("smartkonto") || lower.contains("pocket");
                })
                .findFirst()
                .orElse("");
    }

    private static AccountType suggestType(String product) {
        String lower = product.toLowerCase(Locale.ROOT);
        return SAVINGS_MARKERS.stream().anyMatch(lower::contains) ? AccountType.SAVINGS : AccountType.CHECKING;
    }

    private static BigDecimal abs(BigDecimal value) {
        return value == null ? null : value.abs();
    }
}
