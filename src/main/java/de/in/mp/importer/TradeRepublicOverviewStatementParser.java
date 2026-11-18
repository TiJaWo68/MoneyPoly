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
 * Exposes Trade Republic {@code VERMÖGENSÜBERSICHT} PDFs as bank statement
 * entries ({@code Brokerage} as {@link AccountType#INVESTMENT} and {@code Cash}
 * as {@link AccountType#SAVINGS}) so users can import depot and cash balances
 * via {@link StatementImportService}.
 * <p>
 * Splitting {@code Brokerage} and {@code Cash} prevents double-counting when a
 * user also imports {@code Kontoauszug.pdf} or tracks cash separately, while
 * assigning both to a single combined depot account automatically sums them to
 * {@code GESAMT}.
 */
@Component
public class TradeRepublicOverviewStatementParser implements BankStatementParser {

    static final String BANK = "Trade Republic";
    static final String BROKERAGE_LABEL = "Brokerage";
    static final String CASH_LABEL = "Cashkonto";

    private static final String MARKER_TR = "TRADE REPUBLIC";
    private static final String MARKER_OVERVIEW = "VERMÖGENSÜBERSICHT";
    private static final String MARKER_OVERVIEW_ALT = "VERMOEGENSUEBERSICHT";
    private static final String SECTION_PORTFOLIO = "PORTFOLIO";

    private static final Pattern AS_OF_DATE = Pattern.compile("zum\\s+(" + StatementText.DATE + ")");
    private static final Pattern ANY_DATE = Pattern.compile(StatementText.DATE);
    private static final Pattern DEPOT_NUMBER = Pattern.compile("\\b(\\d{8,12})\\b");
    private static final Pattern AMOUNT = Pattern.compile("(-?\\d{1,3}(?:\\.\\d{3})*,\\d{2})");
    private static final Pattern POSTAL_CODE_LINE = Pattern.compile("^\\d{5}\\s+\\S.*");
    private static final Pattern COMBINED_ADDRESS_LINE = Pattern.compile("^.+?,\\s*\\d{5}\\s+\\S.*");

    @Override
    public boolean supports(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return false;
        }
        String upper = StatementText.normalize(rawText).toUpperCase(Locale.ROOT);
        boolean hasOverview = upper.contains(MARKER_OVERVIEW) || upper.contains(MARKER_OVERVIEW_ALT);
        return upper.contains(MARKER_TR) && hasOverview && upper.contains(SECTION_PORTFOLIO);
    }

    @Override
    public List<StatementEntry> parse(String rawText) {
        String text = StatementText.normalize(rawText);
        if (!supports(text)) {
            throw new IllegalArgumentException("Not a supported Trade Republic wealth overview statement");
        }
        LocalDate closingDate = extractClosingDate(text);
        List<String> lines = StatementText.lines(text).stream().filter(line -> !line.isEmpty()).toList();
        String depotNumber = extractDepotNumber(lines);
        String holder = extractHolder(lines);
        String depotKey = depotNumber != null ? depotNumber : holder.toLowerCase(Locale.ROOT);

        BigDecimal brokerage = findSectionAmount(lines, "BROKERAGE");
        BigDecimal cash = findSectionAmount(lines, "CASH");

        List<StatementEntry> entries = new ArrayList<>();
        if (brokerage != null) {
            entries.add(new StatementEntry(
                    BANK,
                    BROKERAGE_LABEL,
                    AccountType.INVESTMENT,
                    null,
                    depotNumber,
                    holder,
                    closingDate,
                    brokerage,
                    null,
                    null,
                    "TradeRepublic|" + depotKey + "|BROKERAGE"));
        }
        if (cash != null) {
            entries.add(new StatementEntry(
                    BANK,
                    CASH_LABEL,
                    AccountType.SAVINGS,
                    null,
                    depotNumber,
                    holder,
                    closingDate,
                    cash,
                    null,
                    null,
                    "TradeRepublic|" + depotKey + "|CASH"));
        }
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("No PORTFOLIO balances found in Trade Republic overview");
        }
        return entries;
    }

    private static LocalDate extractClosingDate(String text) {
        Matcher asOf = AS_OF_DATE.matcher(text);
        if (asOf.find()) {
            return StatementText.parseDate(asOf.group(1));
        }
        Matcher fallback = ANY_DATE.matcher(text);
        if (fallback.find()) {
            return StatementText.parseDate(fallback.group());
        }
        throw new IllegalArgumentException("Trade Republic overview date not found");
    }

    private static String extractDepotNumber(List<String> lines) {
        for (int i = 0; i < Math.min(lines.size(), 40); i++) {
            String line = lines.get(i);
            if (line.toUpperCase(Locale.ROOT).contains("DEPOT")) {
                for (int j = i; j < Math.min(lines.size(), i + 5); j++) {
                    Matcher m = DEPOT_NUMBER.matcher(lines.get(j));
                    if (m.find()) {
                        return m.group(1);
                    }
                }
            }
        }
        return null;
    }

    private static BigDecimal findSectionAmount(List<String> lines, String labelUpper) {
        int portfolioIdx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).toUpperCase(Locale.ROOT).startsWith(SECTION_PORTFOLIO)) {
                portfolioIdx = i;
                break;
            }
        }
        if (portfolioIdx < 0) {
            return null;
        }
        for (int i = portfolioIdx + 1; i < Math.min(lines.size(), portfolioIdx + 15); i++) {
            String line = lines.get(i);
            String upper = line.toUpperCase(Locale.ROOT);
            if (upper.startsWith("GESAMT")) {
                break;
            }
            if (upper.startsWith(labelUpper)) {
                Matcher sameLine = AMOUNT.matcher(line);
                if (sameLine.find()) {
                    return StatementText.parseAmount(sameLine.group(1));
                }
                if (i + 1 < lines.size()) {
                    Matcher nextLine = AMOUNT.matcher(lines.get(i + 1));
                    if (nextLine.find()) {
                        return StatementText.parseAmount(nextLine.group(1));
                    }
                }
            }
        }
        return null;
    }

    private static String extractHolder(List<String> lines) {
        List<String> cleaned = lines.stream()
                .limit(30)
                .filter(TradeRepublicOverviewStatementParser::isAddressOrHolderLine)
                .toList();
        for (int i = 1; i < cleaned.size(); i++) {
            String line = cleaned.get(i);
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("10119 berlin") || lower.contains("brunnenstr")) {
                continue;
            }
            if (COMBINED_ADDRESS_LINE.matcher(line).matches()) {
                return cleaned.get(i - 1);
            }
            if (i >= 2 && POSTAL_CODE_LINE.matcher(line).matches()) {
                return cleaned.get(i - 2);
            }
        }
        return "";
    }

    private static boolean isAddressOrHolderLine(String line) {
        String upper = line.toUpperCase(Locale.ROOT);
        return !upper.contains(MARKER_TR)
                && !upper.startsWith("DATUM")
                && !upper.startsWith("DEPOT")
                && !ANY_DATE.matcher(line).matches();
    }
}
