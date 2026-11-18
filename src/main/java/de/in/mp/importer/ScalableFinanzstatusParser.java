package de.in.mp.importer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Parses Scalable Capital Bank GmbH {@code Finanzstatus} portfolio overview PDFs
 * to extract security holdings (e.g. {@code Aktien}, {@code Exchange Traded Funds})
 * with quantity, ISIN, EUR market value, and computed unit price.
 */
@Component
public class ScalableFinanzstatusParser implements DepotPositionPdfParser {

    private static final int PRICE_SCALE = 4;
    private static final String MARKER_BANK = "SCALABLE CAPITAL";
    private static final String MARKER_DOC = "FINANZSTATUS";

    private static final Pattern ENTRY_START = Pattern.compile("^(\\d+(?:[.,]\\d{2,}))\\s+(.+)$");
    private static final Pattern ISIN_PATTERN = Pattern.compile("\\b([A-Z]{2}[A-Z0-9]{9}\\d)\\b");
    private static final Pattern EUR_AMOUNT_PATTERN =
            Pattern.compile("(?<![\\d.,-])(\\d{1,3}(?:\\.\\d{3})*,\\d{2})\\s*(?:EUR|€)");

    @Override
    public boolean supports(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return false;
        }
        String upper = StatementText.normalize(rawText).toUpperCase(Locale.ROOT);
        return upper.contains(MARKER_BANK)
                && upper.contains(MARKER_DOC)
                && (upper.contains("STÜCKZAHL") || upper.contains("STUECKZAHL"))
                && upper.contains("POSITIONSWERT");
    }

    @Override
    public List<ImportedDepotPosition> parse(String rawText) {
        if (!supports(rawText)) {
            throw new IllegalArgumentException("Not a supported Scalable Capital Finanzstatus PDF");
        }
        List<String> lines = StatementText.lines(StatementText.normalize(rawText));
        List<ImportedDepotPosition> result = new ArrayList<>();

        boolean inSecurityTable = false;
        BigDecimal currentQty = null;
        StringBuilder nameBuffer = new StringBuilder();
        String currentIsin = null;
        BigDecimal currentMarketValue = null;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (isSecurityTableHeader(line)) {
                flushPending(result, currentQty, nameBuffer, currentIsin, currentMarketValue);
                currentQty = null;
                nameBuffer.setLength(0);
                currentIsin = null;
                currentMarketValue = null;
                inSecurityTable = true;
                continue;
            }
            if (!inSecurityTable) {
                continue;
            }
            if (isTableEnd(line)) {
                flushPending(result, currentQty, nameBuffer, currentIsin, currentMarketValue);
                currentQty = null;
                nameBuffer.setLength(0);
                currentIsin = null;
                currentMarketValue = null;
                inSecurityTable = false;
                continue;
            }

            Matcher startMatcher = ENTRY_START.matcher(line);
            if (startMatcher.matches() && !startsWithAmountOnly(line)) {
                flushPending(result, currentQty, nameBuffer, currentIsin, currentMarketValue);
                currentQty = parseQuantity(startMatcher.group(1));
                nameBuffer.setLength(0);
                currentIsin = null;
                currentMarketValue = null;

                String remainder = startMatcher.group(2).trim();
                Matcher amountInLine = EUR_AMOUNT_PATTERN.matcher(remainder);
                if (amountInLine.find()) {
                    currentMarketValue = StatementText.parseAmount(amountInLine.group(1));
                    remainder = (remainder.substring(0, amountInLine.start())
                            + " " + remainder.substring(amountInLine.end())).trim();
                }
                Matcher isinInLine = ISIN_PATTERN.matcher(remainder);
                if (isinInLine.find()) {
                    currentIsin = isinInLine.group(1);
                    remainder = (remainder.substring(0, isinInLine.start())
                            + " " + remainder.substring(isinInLine.end())).trim();
                }
                appendName(nameBuffer, remainder);
                continue;
            }

            if (currentQty == null) {
                continue;
            }

            String working = line;
            Matcher amountMatcher = EUR_AMOUNT_PATTERN.matcher(working);
            if (amountMatcher.find()) {
                currentMarketValue = StatementText.parseAmount(amountMatcher.group(1));
                working = (working.substring(0, amountMatcher.start())
                        + " " + working.substring(amountMatcher.end())).trim();
            }

            Matcher isinMatcher = ISIN_PATTERN.matcher(working);
            if (isinMatcher.find()) {
                currentIsin = isinMatcher.group(1);
                working = (working.substring(0, isinMatcher.start())
                        + " " + working.substring(isinMatcher.end())).trim();
            }

            appendName(nameBuffer, working);
        }

        flushPending(result, currentQty, nameBuffer, currentIsin, currentMarketValue);

        if (result.isEmpty()) {
            throw new IllegalArgumentException("No brokerage positions found in Scalable Capital Finanzstatus PDF");
        }
        return result;
    }

    private static boolean isSecurityTableHeader(String line) {
        String upper = line.toUpperCase(Locale.ROOT);
        return (upper.contains("STÜCKZAHL") || upper.contains("STUECKZAHL"))
                && upper.contains("WERTPAPIER")
                && upper.contains("POSITIONSWERT");
    }

    private static boolean isTableEnd(String line) {
        String upper = line.toUpperCase(Locale.ROOT);
        return upper.startsWith("GESAMT ")
                || upper.startsWith("GESAMT\t")
                || upper.equals("GESAMT")
                || upper.startsWith("BITTE ÜBERPRÜFEN SIE")
                || upper.startsWith("SCALABLE CAPITAL BANK GMBH");
    }

    private static boolean startsWithAmountOnly(String line) {
        return EUR_AMOUNT_PATTERN.matcher(line).matches();
    }

    private static void appendName(StringBuilder nameBuffer, String part) {
        if (part == null || part.isBlank()) {
            return;
        }
        if (!nameBuffer.isEmpty()) {
            nameBuffer.append(' ');
        }
        nameBuffer.append(part.trim());
    }

    private static void flushPending(
            List<ImportedDepotPosition> result,
            BigDecimal qty,
            StringBuilder nameBuffer,
            String isin,
            BigDecimal marketValue) {
        if (qty == null || qty.signum() <= 0 || isin == null || marketValue == null) {
            return;
        }
        String name = nameBuffer.toString().replaceAll("\\s{2,}", " ").trim();
        if (name.isEmpty()) {
            name = isin;
        }
        BigDecimal unitPriceEur = marketValue.divide(qty, PRICE_SCALE, RoundingMode.HALF_UP);
        result.add(new ImportedDepotPosition(name, isin, qty, unitPriceEur, marketValue));
    }

    /**
     * Parses quantities in English dot-decimal format (e.g. {@code 10.000000},
     * {@code 1,234.500000}) or German format (e.g. {@code 1.234,50}).
     */
    static BigDecimal parseQuantity(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        int lastDot = s.lastIndexOf('.');
        int lastComma = s.lastIndexOf(',');
        if (lastDot >= 0 && lastComma >= 0) {
            if (lastComma > lastDot) {
                return new BigDecimal(s.replace(".", "").replace(',', '.'));
            }
            return new BigDecimal(s.replace(",", ""));
        }
        if (lastComma >= 0) {
            return new BigDecimal(s.replace(',', '.'));
        }
        return new BigDecimal(s);
    }
}
