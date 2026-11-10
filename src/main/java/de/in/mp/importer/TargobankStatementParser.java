package de.in.mp.importer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import de.in.mp.finance.AccountType;

/**
 * Parser for the monthly Targobank "Finanzstatus", which doubles as account
 * statement and lists all accounts of the customer on its first page.
 * <p>
 * Two overview layouts are supported:
 * <ul>
 * <li>current: {@code <product> <number> EUR <balance>}; savings products put
 * the product name and interest rate on the preceding lines,</li>
 * <li>legacy: {@code <balance><number> EUR<product>}.</li>
 * </ul>
 * Monthly totals (debits first, credits second) are taken from the
 * {@code Summe} line that closes the transaction section of each account.
 * Accounts without a transaction section (e.g. savings books) get no flows.
 */
@Component
public class TargobankStatementParser implements BankStatementParser {

    static final String BANK = "Targobank";

    private static final String BANK_MARKER = "TARGOBANK";
    private static final String STATUS_MARKER = "F I N A N Z S T A T U S";
    private static final String OVERVIEW_END = "Gesamtguthaben";

    private static final String STRICT_AMOUNT = "-?\\d{1,3}(?:\\.\\d{3})*,\\d{2}";
    private static final Pattern PERIOD = Pattern.compile(
            "vom\\s+" + StatementText.DATE + "\\s*-\\s*(" + StatementText.DATE + ")");
    private static final Pattern CURRENT_LAYOUT = Pattern.compile(
            "^(.*?)\\s*(?<![\\d,])(\\d{10})\\s*EUR\\s*(" + STRICT_AMOUNT + ")$");
    private static final Pattern LEGACY_LAYOUT = Pattern.compile(
            "^(" + STRICT_AMOUNT + ")(\\d{10})\\s*EUR\\s*(.+)$");
    private static final Pattern IBAN = Pattern.compile("IBAN:\\s*(DE\\d{2}(?:\\s?\\d{4}){4}\\s?\\d{2})");
    private static final Pattern SUM_LINE = Pattern.compile(
            "^Summe\\s*(" + STRICT_AMOUNT + ")\\s+(" + STRICT_AMOUNT + ")$");
    private static final Pattern SALUTATION = Pattern.compile("^(Herr|Frau)$");
    private static final Pattern TRAILING_RULE = Pattern.compile("_{3,}$");
    private static final int GERMAN_ACCOUNT_NUMBER_LENGTH = 10;

    @Override
    public boolean supports(String text) {
        return text != null && text.toUpperCase(Locale.ROOT).contains(BANK_MARKER) && text.contains(STATUS_MARKER);
    }

    @Override
    public List<StatementEntry> parse(String rawText) {
        String text = StatementText.normalize(rawText);
        Matcher period = PERIOD.matcher(text);
        if (!period.find()) {
            throw new IllegalArgumentException("Targobank statement period not found");
        }
        LocalDate closingDate = StatementText.parseDate(period.group(1));

        List<String> lines = StatementText.lines(text).stream().map(TargobankStatementParser::cleanLine).toList();
        int overviewEnd = indexOf(lines, OVERVIEW_END);
        if (overviewEnd < 0) {
            throw new IllegalArgumentException("Targobank account overview not found");
        }

        Map<String, String> ibanByAccountNumber = collectIbans(text);
        String holder = findHolder(lines);

        List<StatementEntry> entries = new ArrayList<>();
        for (OverviewLine overview : parseOverview(lines, overviewEnd)) {
            BigDecimal[] flows = findFlows(lines, overviewEnd, overview.accountNumber());
            String iban = ibanByAccountNumber.get(overview.accountNumber());
            String importKey = iban != null ? "IBAN|" + iban : BANK + "|" + overview.accountNumber();
            entries.add(new StatementEntry(BANK, overview.label(), suggestType(overview.label()), iban,
                    overview.accountNumber(), holder, closingDate, overview.balance(),
                    flows == null ? null : flows[1], flows == null ? null : flows[0], importKey));
        }
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("No accounts found in Targobank statement");
        }
        return entries;
    }

    /**
     * Removes horizontal rules glued to the line end and collapses the column
     * padding produced by PDF text extraction.
     */
    private static String cleanLine(String line) {
        return TRAILING_RULE.matcher(line).replaceAll("").replaceAll("\\s+", " ").trim();
    }

    private static List<OverviewLine> parseOverview(List<String> lines, int overviewEnd) {
        Map<String, OverviewLine> byNumber = new LinkedHashMap<>();
        for (int i = 0; i < overviewEnd; i++) {
            String line = lines.get(i);
            Matcher legacy = LEGACY_LAYOUT.matcher(line);
            if (legacy.matches()) {
                byNumber.putIfAbsent(legacy.group(2), new OverviewLine(legacy.group(2),
                        legacy.group(3).trim(), StatementText.parseAmount(legacy.group(1))));
                continue;
            }
            Matcher current = CURRENT_LAYOUT.matcher(line);
            if (current.matches()) {
                String label = current.group(1).isBlank() ? previousLabel(lines, i) : current.group(1);
                byNumber.putIfAbsent(current.group(2), new OverviewLine(current.group(2),
                        label.trim(), StatementText.parseAmount(current.group(3))));
            }
        }
        return new ArrayList<>(byNumber.values());
    }

    /**
     * Savings products print name and interest rate above the number line.
     */
    private static String previousLabel(List<String> lines, int index) {
        for (int i = index - 1; i >= 0; i--) {
            String line = lines.get(i);
            if (line.isEmpty() || line.startsWith("Zinssatz") || line.endsWith("%")) {
                continue;
            }
            return line;
        }
        return "";
    }

    /**
     * The first {@code Summe} line after the account's section header holds
     * the monthly debits and credits.
     */
    private static BigDecimal[] findFlows(List<String> lines, int overviewEnd, String accountNumber) {
        int sectionStart = -1;
        for (int i = overviewEnd + 1; i < lines.size(); i++) {
            if (lines.get(i).contains(accountNumber)) {
                sectionStart = i;
                break;
            }
        }
        if (sectionStart < 0) {
            return null;
        }
        for (int i = sectionStart + 1; i < lines.size(); i++) {
            Matcher sum = SUM_LINE.matcher(lines.get(i));
            if (sum.matches()) {
                return new BigDecimal[] {
                        StatementText.parseAmount(sum.group(1)).abs(),
                        StatementText.parseAmount(sum.group(2)).abs() };
            }
        }
        return null;
    }

    private static Map<String, String> collectIbans(String text) {
        Map<String, String> result = new HashMap<>();
        Matcher matcher = IBAN.matcher(text);
        while (matcher.find()) {
            String iban = StatementText.stripSpaces(matcher.group(1));
            result.putIfAbsent(iban.substring(iban.length() - GERMAN_ACCOUNT_NUMBER_LENGTH), iban);
        }
        return result;
    }

    private static String findHolder(List<String> lines) {
        for (int i = 0; i < lines.size() - 1; i++) {
            if (SALUTATION.matcher(lines.get(i)).matches()) {
                return lines.get(i + 1);
            }
        }
        return null;
    }

    private static int indexOf(List<String> lines, String prefix) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    private static AccountType suggestType(String label) {
        String lower = label.toLowerCase(Locale.ROOT);
        if (lower.contains("spar") || lower.contains("tagesgeld") || lower.contains("festgeld")) {
            return AccountType.SAVINGS;
        }
        if (lower.contains("depot")) {
            return AccountType.INVESTMENT;
        }
        return AccountType.CHECKING;
    }

    private record OverviewLine(String accountNumber, String label, BigDecimal balance) {
    }
}
