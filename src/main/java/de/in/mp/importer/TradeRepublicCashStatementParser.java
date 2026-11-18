package de.in.mp.importer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import de.in.mp.finance.AccountType;

/**
 * Parser for Trade Republic cash account statements ({@code Kontoauszug.pdf}).
 * <p>
 * Extracts the {@code Cashkonto} summary ({@code KONTOÜBERSICHT}) with IBAN,
 * closing date and closing balance ({@code ENDSALDO}). Monthly inflow/outflow
 * totals are only recorded when the statement spans at most one billing month
 * so multi-year full exports do not distort single-month cashflow statistics.
 */
@Component
public class TradeRepublicCashStatementParser implements BankStatementParser {

    static final String BANK = "Trade Republic";
    static final String CASH_LABEL = "Cashkonto";

    private static final String BANK_MARKER = "TRADE REPUBLIC";
    private static final String OVERVIEW_MARKER = "KONTOÜBERSICHT";
    private static final long MAX_SINGLE_MONTH_DAYS = 35;

    private static final Pattern PERIOD = Pattern.compile(
            "(\\d{1,2})\\.?\\s+([\\p{L}]+)\\.?\\s+(\\d{4})\\s*-\\s*(\\d{1,2})\\.?\\s+([\\p{L}]+)\\.?\\s+(\\d{4})");
    private static final Pattern IBAN = Pattern.compile("\\b(DE\\d{2}(?:\\s?\\d{4}){4}\\s?\\d{2})\\b");
    private static final Pattern AMOUNT = Pattern.compile("(-?\\d{1,3}(?:\\.\\d{3})*,\\d{2})\\s*(?:€|EUR)?");
    private static final Pattern POSTAL_CODE_LINE = Pattern.compile("^\\d{5}\\s+\\S.*");
    private static final Pattern COMBINED_ADDRESS_LINE = Pattern.compile("^.+?,\\s*\\d{5}\\s+\\S.*");

    private static final Map<String, Month> GERMAN_MONTHS = Map.ofEntries(
            Map.entry("jan", Month.JANUARY), Map.entry("januar", Month.JANUARY),
            Map.entry("feb", Month.FEBRUARY), Map.entry("februar", Month.FEBRUARY),
            Map.entry("mär", Month.MARCH), Map.entry("maer", Month.MARCH), Map.entry("märz", Month.MARCH),
            Map.entry("apr", Month.APRIL), Map.entry("april", Month.APRIL),
            Map.entry("mai", Month.MAY),
            Map.entry("jun", Month.JUNE), Map.entry("juni", Month.JUNE),
            Map.entry("jul", Month.JULY), Map.entry("juli", Month.JULY),
            Map.entry("aug", Month.AUGUST), Map.entry("august", Month.AUGUST),
            Map.entry("sep", Month.SEPTEMBER), Map.entry("sept", Month.SEPTEMBER),
            Map.entry("september", Month.SEPTEMBER),
            Map.entry("okt", Month.OCTOBER), Map.entry("oktober", Month.OCTOBER),
            Map.entry("nov", Month.NOVEMBER), Map.entry("november", Month.NOVEMBER),
            Map.entry("dez", Month.DECEMBER), Map.entry("dezember", Month.DECEMBER));

    @Override
    public boolean supports(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return false;
        }
        String upper = StatementText.normalize(rawText).toUpperCase(Locale.ROOT);
        return upper.contains(BANK_MARKER)
                && upper.contains(OVERVIEW_MARKER)
                && upper.contains("CASHKONTO");
    }

    @Override
    public List<StatementEntry> parse(String rawText) {
        String text = StatementText.normalize(rawText);
        if (!supports(text)) {
            throw new IllegalArgumentException("Not a supported Trade Republic cash statement");
        }
        Matcher period = PERIOD.matcher(text);
        if (!period.find()) {
            throw new IllegalArgumentException("Trade Republic statement period not found");
        }
        LocalDate startDate = parseGermanWordDate(period.group(1), period.group(2), period.group(3));
        LocalDate closingDate = parseGermanWordDate(period.group(4), period.group(5), period.group(6));

        Matcher ibanMatcher = IBAN.matcher(text);
        String iban = ibanMatcher.find() ? StatementText.stripSpaces(ibanMatcher.group(1)) : null;

        List<String> lines = StatementText.lines(text).stream().filter(line -> !line.isEmpty()).toList();
        String holder = extractHolder(lines);
        BigDecimal[] summaryAmounts = extractCashSummaryAmounts(lines);
        if (summaryAmounts.length < 4) {
            throw new IllegalArgumentException("Trade Republic Cashkonto summary amounts not found");
        }

        BigDecimal inflow = null;
        BigDecimal outflow = null;
        if (ChronoUnit.DAYS.between(startDate, closingDate) <= MAX_SINGLE_MONTH_DAYS) {
            inflow = summaryAmounts[1].abs();
            outflow = summaryAmounts[2].abs();
        }
        BigDecimal closingBalance = summaryAmounts[3];

        String importKey = iban != null
                ? "IBAN|" + iban
                : BANK + "|" + holder.toLowerCase(Locale.ROOT) + "|" + CASH_LABEL.toLowerCase(Locale.ROOT);

        return List.of(new StatementEntry(
                BANK,
                CASH_LABEL,
                AccountType.SAVINGS,
                iban,
                null,
                holder,
                closingDate,
                closingBalance,
                inflow,
                outflow,
                importKey));
    }

    private static BigDecimal[] extractCashSummaryAmounts(List<String> lines) {
        int overviewIdx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).toUpperCase(Locale.ROOT).contains(OVERVIEW_MARKER)) {
                overviewIdx = i;
                break;
            }
        }
        if (overviewIdx < 0) {
            return new BigDecimal[0];
        }
        int cashLineIdx = -1;
        for (int i = overviewIdx; i < Math.min(lines.size(), overviewIdx + 15); i++) {
            if (lines.get(i).toUpperCase(Locale.ROOT).startsWith("CASHKONTO")) {
                cashLineIdx = i;
                break;
            }
        }
        if (cashLineIdx < 0) {
            return new BigDecimal[0];
        }
        BigDecimal[] amounts = new BigDecimal[4];
        int count = 0;
        for (int i = cashLineIdx; i < Math.min(lines.size(), cashLineIdx + 8) && count < 4; i++) {
            String line = lines.get(i);
            if (i > cashLineIdx && line.toUpperCase(Locale.ROOT).contains("UMSATZÜBERSICHT")) {
                break;
            }
            Matcher matcher = AMOUNT.matcher(line);
            while (matcher.find() && count < 4) {
                amounts[count++] = StatementText.parseAmount(matcher.group(1));
            }
        }
        return count == 4 ? amounts : new BigDecimal[0];
    }

    private static String extractHolder(List<String> lines) {
        List<String> cleaned = lines.stream()
                .limit(30)
                .filter(TradeRepublicCashStatementParser::isAddressOrHolderLine)
                .toList();
        for (int i = 1; i < cleaned.size(); i++) {
            String line = cleaned.get(i);
            if (isBankAddress(line)) {
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
        return !upper.contains(BANK_MARKER)
                && !upper.startsWith("IBAN")
                && !upper.startsWith("BIC")
                && !upper.startsWith("DATUM")
                && !upper.startsWith("TRBKDE")
                && !IBAN.matcher(upper).matches()
                && !PERIOD.matcher(line).find();
    }

    private static boolean isBankAddress(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("10119 berlin") || lower.contains("brunnenstr");
    }

    private static LocalDate parseGermanWordDate(String dayStr, String monthStr, String yearStr) {
        String normalizedMonth = monthStr.toLowerCase(Locale.ROOT).replace(".", "");
        Month month = GERMAN_MONTHS.get(normalizedMonth);
        if (month == null) {
            throw new IllegalArgumentException("Unknown German month in Trade Republic statement: " + monthStr);
        }
        return LocalDate.of(Integer.parseInt(yearStr), month, Integer.parseInt(dayStr));
    }
}
