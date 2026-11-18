package de.in.mp.importer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Parses Trade Republic {@code VERMÖGENSÜBERSICHT} PDF statements to extract
 * brokerage positions with quantity, ISIN and EUR reference price. Supports
 * both line-wrapped and tabular single-line PDFBox text layouts across page
 * breaks.
 */
@Component
public class TradeRepublicStatementParser implements DepotPositionPdfParser {

    private static final String MARKER_TR = "TRADE REPUBLIC";
    private static final String MARKER_OVERVIEW = "VERMÖGENSÜBERSICHT";
    private static final String MARKER_OVERVIEW_ALT = "VERMOEGENSUEBERSICHT";
    private static final String SECTION_BROKERAGE = "BROKERAGE";
    private static final String SECTION_CASH = "CASH";
    private static final String SECTION_END_PREFIX = "ANZAHL POSITIONEN";

    private static final String STRICT_EUR_AMOUNT = "(?<![\\d.,-])\\d{1,3}(?:\\.\\d{3})*,\\d{2}(?![\\d,])";
    private static final Pattern ENTRY_START = Pattern.compile(
            "^(\\d{1,3}(?:\\.\\d{3})*(?:,\\d+)?|\\d+(?:,\\d+)?)\\s*Stk\\.?\\s+(.+)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ISIN_PATTERN = Pattern.compile("ISIN:\\s*([A-Z]{2}[A-Z0-9]{10})\\b");
    private static final Pattern PRICE_WITH_DATE = Pattern.compile(
            "(" + STRICT_EUR_AMOUNT + ")\\s+" + StatementText.DATE);
    private static final Pattern AMOUNT_TOKEN = Pattern.compile(STRICT_EUR_AMOUNT);
    private static final Pattern TRAILING_PRICE_COLUMNS = Pattern.compile(
            "(?:\\s+(?:" + StatementText.DATE + "|" + STRICT_EUR_AMOUNT + "))+$");
    private static final Pattern PAGE_MARKER = Pattern.compile("^Seite\\s+\\d+\\s+von\\s+\\d+$", Pattern.CASE_INSENSITIVE);

    private static final List<String> BOILERPLATE_PREFIXES = List.of(
            "TRADE REPUBLIC",
            "STK. / NOMINALE",
            "AUFSTELLUNG ÜBER DIE BROKERAGE",
            "BRUNNENSTRASSE",
            "WWW.TRADEREPUBLIC.COM",
            "SITZ DER GESELLSCHAFT",
            "AG CHARLOTTENBURG",
            "UMSATZSTEUER-ID",
            "GESCHÄFTSFÜHRER",
            "ERSTELLT AM ");

    @Override
    public boolean supports(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return false;
        }
        String upper = StatementText.normalize(rawText).toUpperCase(Locale.ROOT);
        boolean hasTr = upper.contains(MARKER_TR);
        boolean hasOverview = upper.contains(MARKER_OVERVIEW) || upper.contains(MARKER_OVERVIEW_ALT);
        return hasTr && hasOverview && upper.contains(SECTION_BROKERAGE);
    }

    @Override
    public List<ImportedDepotPosition> parse(String rawText) {
        if (!supports(rawText)) {
            throw new IllegalArgumentException("Not a supported Trade Republic wealth overview PDF");
        }
        List<String> lines = StatementText.lines(StatementText.normalize(rawText));
        int brokerageStart = findBrokerageSectionStart(lines);
        if (brokerageStart < 0) {
            throw new IllegalArgumentException("Brokerage section not found in Trade Republic PDF");
        }

        List<ImportedDepotPosition> entries = parseBrokerageBlocks(lines, brokerageStart + 1);
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("No brokerage positions found in Trade Republic PDF");
        }
        return entries;
    }

    private static int findBrokerageSectionStart(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (SECTION_BROKERAGE.equalsIgnoreCase(lines.get(i).trim())) {
                return i;
            }
        }
        return -1;
    }

    private List<ImportedDepotPosition> parseBrokerageBlocks(List<String> lines, int startIndex) {
        List<ImportedDepotPosition> result = new ArrayList<>();
        BigDecimal currentQty = null;
        String currentName = null;
        StringBuilder blockText = new StringBuilder();

        for (int i = startIndex; i < lines.size(); i++) {
            String line = lines.get(i).replaceAll("\\s+", " ").trim();
            if (line.isEmpty() || isBoilerplateLine(line)) {
                continue;
            }
            String upper = line.toUpperCase(Locale.ROOT);
            if (upper.startsWith(SECTION_END_PREFIX) || SECTION_CASH.equals(upper)) {
                break;
            }
            Matcher start = ENTRY_START.matcher(line);
            if (start.matches()) {
                flushBlock(result, currentQty, currentName, blockText);
                currentQty = StatementText.parseAmount(start.group(1));
                String headerRemainder = start.group(2).trim();
                currentName = stripTrailingColumns(headerRemainder);
                blockText.setLength(0);
                blockText.append(headerRemainder).append('\n');
            } else if (currentQty != null) {
                blockText.append(line).append('\n');
            }
        }
        flushBlock(result, currentQty, currentName, blockText);
        return result;
    }

    private static void flushBlock(
            List<ImportedDepotPosition> target,
            BigDecimal quantity,
            String name,
            StringBuilder blockText) {
        if (quantity == null || name == null || name.isBlank()) {
            return;
        }
        String text = blockText.toString();
        String isin = extractIsin(text);
        if (isin == null) {
            return;
        }
        BigDecimal[] priceAndValue = extractPriceAndValue(text);
        target.add(new ImportedDepotPosition(name, isin, quantity, priceAndValue[0], priceAndValue[1]));
    }

    private static String extractIsin(String blockText) {
        Matcher matcher = ISIN_PATTERN.matcher(blockText.toUpperCase(Locale.ROOT));
        return matcher.find() ? matcher.group(1) : null;
    }

    private static BigDecimal[] extractPriceAndValue(String blockText) {
        // Unit price precedes the quote date in both line-wrapped and tabular TR layouts
        Matcher priceMatcher = PRICE_WITH_DATE.matcher(blockText);
        BigDecimal unitPrice = priceMatcher.find() ? StatementText.parseAmount(priceMatcher.group(1)) : null;

        List<BigDecimal> amounts = new ArrayList<>();
        Matcher amountMatcher = AMOUNT_TOKEN.matcher(blockText);
        while (amountMatcher.find()) {
            amounts.add(StatementText.parseAmount(amountMatcher.group()));
        }

        if (unitPrice == null && !amounts.isEmpty()) {
            unitPrice = amounts.get(0);
        }
        BigDecimal marketValue = amounts.size() >= 2 ? amounts.get(amounts.size() - 1) : null;
        return new BigDecimal[] { unitPrice, marketValue };
    }

    private static String stripTrailingColumns(String remainder) {
        return TRAILING_PRICE_COLUMNS.matcher(remainder).replaceAll("").trim();
    }

    private static boolean isBoilerplateLine(String line) {
        if (PAGE_MARKER.matcher(line).matches()) {
            return true;
        }
        String upper = line.toUpperCase(Locale.ROOT).replace("ẞ", "SS");
        if (SECTION_BROKERAGE.equals(upper)) {
            return true;
        }
        for (String prefix : BOILERPLATE_PREFIXES) {
            if (upper.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
