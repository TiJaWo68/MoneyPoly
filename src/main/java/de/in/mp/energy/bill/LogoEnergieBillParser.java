package de.in.mp.energy.bill;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Parser for LogoEnergie GmbH electricity and gas annual settlement PDFs.
 */
@Component
public class LogoEnergieBillParser implements UtilityBillParser {

    private static final DateTimeFormatter NUMERIC_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter LONG_GERMAN_DATE = DateTimeFormatter.ofPattern("d. MMMM yyyy", Locale.GERMAN);

    private static final Pattern PROVIDER_EMAIL_PATTERN =
            Pattern.compile("E-Mail:\\s*([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})");
    private static final Pattern CUSTOMER_NUMBER_PATTERN =
            Pattern.compile("Kunden-Nr\\.?\\s+([A-Za-z0-9./-]+)");
    private static final Pattern INVOICE_NUMBER_PATTERN =
            Pattern.compile("Rechnungsnummer\\s+([A-Za-z0-9/_-]+)");
    private static final Pattern INVOICE_DATE_PATTERN =
            Pattern.compile("Rechnungsdatum\\s+(\\d{1,2}\\.\\s*[A-Za-zäöüÄÖÜ]+\\s+\\d{4}|\\d{2}\\.\\d{2}\\.\\d{4})");
    private static final Pattern BILLING_PERIOD_PATTERN =
            Pattern.compile("Abrechnungszeitraum:\\s*(\\d{2}\\.\\d{2}\\.\\d{4})\\s*-\\s*(\\d{2}\\.\\d{2}\\.\\d{4})");
    private static final Pattern SUMMARY_ROW_PATTERN = Pattern.compile(
            "(?:Strom|Gas)\\s+[\\d.,]+\\s*kWh\\s*\\(\\d+\\)\\s+([\\d.]+(?:,\\d+)?)\\s*kWh\\s*\\((\\d+)\\)\\s+([\\d.]+,\\d{2})\\s+([\\d.]+,\\d{2})\\s*\\((\\d+(?:,\\d+)?)%\\)\\s+([\\d.]+,\\d{2})");
    private static final Pattern GESAMTBETRAG_PATTERN =
            Pattern.compile("Gesamtbetrag\\s+([\\d.]+,\\d{2})\\s+([\\d.]+,\\d{2})\\s+([\\d.]+,\\d{2})");
    private static final Pattern PREPAYMENTS_PATTERN =
            Pattern.compile("bis\\s+\\d{2}\\.\\d{2}\\.\\d{4}\\s+gezahlt[^\\n]*\\s(-?[\\d.]+,\\d{2})\\s*$", Pattern.MULTILINE);
    private static final Pattern AMOUNT_DUE_PATTERN =
            Pattern.compile("zu zahlender Betrag[^\\n]*\\s(-?[\\d.]+,\\d{2})\\s*$", Pattern.MULTILINE);
    private static final Pattern CREDIT_DUE_PATTERN =
            Pattern.compile("(?:Guthaben|Erstattungsbetrag)[^\\n]*\\s(-?[\\d.]+,\\d{2})\\s*$", Pattern.MULTILINE);
    private static final Pattern INSTALLMENT_ROW_PATTERN = Pattern.compile(
            "Neuer Abschlag[\\s\\S]{0,120}?(?:Strom|Gas)\\s+([\\d.]+,\\d{2})\\s+\\d+(?:,\\d+)?\\s+([\\d.]+,\\d{2})\\s+([\\d.]+,\\d{2})");
    private static final Pattern METER_SERIAL_PATTERN =
            Pattern.compile("Zählernummer\\s+([A-Za-z0-9-]+)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern TARIFF_NAME_PATTERN =
            Pattern.compile("Tarif\\s+[\"„]([^\"“]+)[\"“]");
    private static final Pattern FOOTNOTE_PATTERN =
            Pattern.compile("^\\s*(\\d+\\))\\s+(.+)$");
    private static final Pattern PERIOD_ROW_PATTERN = Pattern.compile(
            "^\\s*(\\d{2}\\.\\d{2}\\.\\d{4})\\s*-\\s*(\\d{2}\\.\\d{2}\\.\\d{4})\\s+"
                    + "([\\d.]+(?:,\\d+)?)\\s+(?:(\\d+\\))\\s+)?"
                    + "([\\d.]+(?:,\\d+)?)\\s+(?:(\\d+\\))\\s+)?"
                    + "([\\d.]+(?:,\\d+)?)\\s+([\\d.]+(?:,\\d+)?)\\s+kWh\\s*$");
    private static final Pattern AP_LINE_PATTERN = Pattern.compile(
            "^\\s*(\\d{2}\\.\\d{2}\\.\\d{4})\\s*-\\s*(\\d{2}\\.\\d{2}\\.\\d{4})\\s+(AP)\\s+(.+?)\\s+"
                    + "([\\d.]+(?:,\\d+)?)\\s+(Ct/kWh|€/kWh|EUR/kWh)\\s+([\\d.]+(?:,\\d+)?)\\s+(kWh)\\s+"
                    + "(\\d+(?:,\\d+)?)%\\s+([\\d.]+,\\d{2})\\s*€\\s*$");
    private static final Pattern GP_LINE_PATTERN = Pattern.compile(
            "^\\s*(\\d{2}\\.\\d{2}\\.\\d{4})\\s*-\\s*(\\d{2}\\.\\d{2}\\.\\d{4})\\s+(GP)\\s+(.+?)\\s+"
                    + "([\\d.]+(?:,\\d+)?)\\s+(€/Jahr|€/Monat|EUR/Jahr|EUR/Monat)\\s+(\\d+(?:,\\d+)?)\\s+(Tage|Tag|Monate|Monat)\\s+"
                    + "(\\d+(?:,\\d+)?)%\\s+([\\d.]+,\\d{2})\\s*€\\s*$");

    @Override
    public boolean supports(String extractedText) {
        if (extractedText == null || extractedText.isBlank()) {
            return false;
        }
        String lower = extractedText.toLowerCase(Locale.ROOT);
        return lower.contains("logoenergie")
                && (lower.contains("abrechnungszeitraum") || lower.contains("übersicht der verbrauchsmengen"));
    }

    @Override
    public UtilityBill parse(String extractedText) {
        if (!supports(extractedText)) {
            throw new IllegalArgumentException("Text is not a supported LogoEnergie invoice");
        }

        String normalized = normalizeSpaces(extractedText);
        String[] lines = normalized.split("\\r?\\n");

        String providerEmail = matchGroup(PROVIDER_EMAIL_PATTERN, normalized, 1);
        String customerNumber = matchGroup(CUSTOMER_NUMBER_PATTERN, normalized, 1);
        String invoiceNumber = matchGroup(INVOICE_NUMBER_PATTERN, normalized, 1);
        LocalDate invoiceDate = parseGermanDate(matchGroup(INVOICE_DATE_PATTERN, normalized, 1));

        LocalDate periodStart = null;
        LocalDate periodEnd = null;
        Matcher periodMatcher = BILLING_PERIOD_PATTERN.matcher(normalized);
        if (periodMatcher.find()) {
            periodStart = parseGermanDate(periodMatcher.group(1));
            periodEnd = parseGermanDate(periodMatcher.group(2));
        }

        BigDecimal totalConsumptionKwh = null;
        Integer billedDays = null;
        BigDecimal totalNetEur = null;
        BigDecimal vatRatePercent = null;
        BigDecimal totalVatEur = null;
        BigDecimal totalGrossEur = null;

        Matcher summaryMatcher = SUMMARY_ROW_PATTERN.matcher(normalized);
        if (summaryMatcher.find()) {
            totalConsumptionKwh = parseGermanDecimal(summaryMatcher.group(1));
            billedDays = Integer.parseInt(summaryMatcher.group(2));
            totalNetEur = parseGermanDecimal(summaryMatcher.group(3));
            totalVatEur = parseGermanDecimal(summaryMatcher.group(4));
            vatRatePercent = parseGermanDecimal(summaryMatcher.group(5));
            totalGrossEur = parseGermanDecimal(summaryMatcher.group(6));
        } else {
            Matcher gesamtMatcher = GESAMTBETRAG_PATTERN.matcher(normalized);
            if (gesamtMatcher.find()) {
                totalNetEur = parseGermanDecimal(gesamtMatcher.group(1));
                totalVatEur = parseGermanDecimal(gesamtMatcher.group(2));
                totalGrossEur = parseGermanDecimal(gesamtMatcher.group(3));
            }
        }

        BigDecimal prepaymentsGrossEur = BigDecimal.ZERO;
        String prepaymentRaw = matchGroup(PREPAYMENTS_PATTERN, normalized, 1);
        if (prepaymentRaw != null) {
            prepaymentsGrossEur = parseGermanDecimal(prepaymentRaw).abs();
        }

        BigDecimal amountDueGrossEur = null;
        String dueRaw = matchGroup(AMOUNT_DUE_PATTERN, normalized, 1);
        if (dueRaw != null) {
            amountDueGrossEur = parseGermanDecimal(dueRaw);
        } else {
            String creditRaw = matchGroup(CREDIT_DUE_PATTERN, normalized, 1);
            if (creditRaw != null) {
                BigDecimal credit = parseGermanDecimal(creditRaw);
                amountDueGrossEur = credit.signum() > 0 ? credit.negate() : credit;
            }
        }

        BigDecimal newInstallmentNetEur = null;
        BigDecimal newInstallmentGrossEur = null;
        Matcher installmentMatcher = INSTALLMENT_ROW_PATTERN.matcher(normalized);
        if (installmentMatcher.find()) {
            newInstallmentNetEur = parseGermanDecimal(installmentMatcher.group(1));
            newInstallmentGrossEur = parseGermanDecimal(installmentMatcher.group(3));
        }

        String meterSerial = matchGroup(METER_SERIAL_PATTERN, normalized, 1);
        String tariffName = matchGroup(TARIFF_NAME_PATTERN, normalized, 1);

        Map<String, UtilityBillReadingSource> footnotes = parseFootnotes(lines);
        List<UtilityBillPeriod> periods = parsePeriods(lines, footnotes);
        List<UtilityBillPriceLine> priceLines = parsePriceLines(lines);

        if (totalConsumptionKwh == null && !periods.isEmpty()) {
            totalConsumptionKwh = periods.stream()
                    .map(UtilityBillPeriod::consumptionKwh)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        return new UtilityBill(
                "LogoEnergie GmbH",
                providerEmail,
                customerNumber,
                invoiceNumber,
                invoiceDate,
                periodStart,
                periodEnd,
                billedDays,
                meterSerial,
                tariffName,
                totalConsumptionKwh,
                totalNetEur,
                vatRatePercent,
                totalVatEur,
                totalGrossEur,
                prepaymentsGrossEur,
                amountDueGrossEur,
                newInstallmentNetEur,
                newInstallmentGrossEur,
                List.copyOf(periods),
                List.copyOf(priceLines));
    }

    private Map<String, UtilityBillReadingSource> parseFootnotes(String[] lines) {
        Map<String, UtilityBillReadingSource> result = new HashMap<>();
        for (String line : lines) {
            Matcher matcher = FOOTNOTE_PATTERN.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String key = matcher.group(1);
            String text = matcher.group(2).toLowerCase(Locale.GERMAN);
            if (text.contains("geschätzt") || text.contains("schätzung") || text.contains("ersatzwert")) {
                result.put(key, UtilityBillReadingSource.ESTIMATED);
            } else if (text.contains("abgelesen")) {
                result.put(key, UtilityBillReadingSource.METER_READ);
            }
        }
        return result;
    }

    private List<UtilityBillPeriod> parsePeriods(String[] lines, Map<String, UtilityBillReadingSource> footnotes) {
        List<UtilityBillPeriod> periods = new ArrayList<>();
        for (String line : lines) {
            Matcher matcher = PERIOD_ROW_PATTERN.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            LocalDate from = parseGermanDate(matcher.group(1));
            LocalDate to = parseGermanDate(matcher.group(2));
            BigDecimal oldReading = parseGermanDecimal(matcher.group(3));
            String oldNote = matcher.group(4);
            BigDecimal newReading = parseGermanDecimal(matcher.group(5));
            String newNote = matcher.group(6);
            BigDecimal consumptionKwh = parseGermanDecimal(matcher.group(8));

            UtilityBillReadingSource source = resolveSource(oldNote, newNote, footnotes);
            String note = newNote != null ? newNote : oldNote;
            periods.add(new UtilityBillPeriod(from, to, oldReading, newReading, consumptionKwh, source, note));
        }
        return periods;
    }

    private UtilityBillReadingSource resolveSource(
            String oldNote, String newNote, Map<String, UtilityBillReadingSource> footnotes) {
        UtilityBillReadingSource newSource = newNote != null ? footnotes.get(newNote) : null;
        if (newSource == UtilityBillReadingSource.ESTIMATED) {
            return UtilityBillReadingSource.ESTIMATED;
        }
        UtilityBillReadingSource oldSource = oldNote != null ? footnotes.get(oldNote) : null;
        if (oldSource == UtilityBillReadingSource.ESTIMATED) {
            return UtilityBillReadingSource.ESTIMATED;
        }
        if (newSource != null) {
            return newSource;
        }
        if (oldSource != null) {
            return oldSource;
        }
        return UtilityBillReadingSource.UNKNOWN;
    }

    private List<UtilityBillPriceLine> parsePriceLines(String[] lines) {
        List<UtilityBillPriceLine> priceLines = new ArrayList<>();
        for (String line : lines) {
            Matcher ap = AP_LINE_PATTERN.matcher(line);
            if (ap.matches()) {
                BigDecimal rawPrice = parseGermanDecimal(ap.group(5));
                String priceUnit = ap.group(6);
                BigDecimal unitPriceEur = priceUnit.startsWith("Ct") ? rawPrice.movePointLeft(2) : rawPrice;
                priceLines.add(new UtilityBillPriceLine(
                        parseGermanDate(ap.group(1)),
                        parseGermanDate(ap.group(2)),
                        ap.group(3),
                        ap.group(4).trim(),
                        unitPriceEur,
                        false,
                        parseGermanDecimal(ap.group(7)),
                        ap.group(8),
                        parseGermanDecimal(ap.group(9)),
                        parseGermanDecimal(ap.group(10))));
                continue;
            }
            Matcher gp = GP_LINE_PATTERN.matcher(line);
            if (gp.matches()) {
                BigDecimal rawPrice = parseGermanDecimal(gp.group(5));
                String priceUnit = gp.group(6);
                boolean perYear = priceUnit.endsWith("Jahr");
                priceLines.add(new UtilityBillPriceLine(
                        parseGermanDate(gp.group(1)),
                        parseGermanDate(gp.group(2)),
                        gp.group(3),
                        gp.group(4).trim(),
                        rawPrice,
                        perYear,
                        parseGermanDecimal(gp.group(7)),
                        gp.group(8),
                        parseGermanDecimal(gp.group(9)),
                        parseGermanDecimal(gp.group(10))));
            }
        }
        return priceLines;
    }

    private static String normalizeSpaces(String text) {
        return text.replace('\u00A0', ' ').replaceAll("[ \\t]+", " ");
    }

    private static String matchGroup(Pattern pattern, String input, int group) {
        Matcher m = pattern.matcher(input);
        return m.find() ? m.group(group).trim() : null;
    }

    static BigDecimal parseGermanDecimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().replace(".", "").replace(',', '.');
        return new BigDecimal(normalized);
    }

    static LocalDate parseGermanDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.trim().replaceAll("\\s+", " ");
        if (cleaned.matches("\\d{2}\\.\\d{2}\\.\\d{4}")) {
            return LocalDate.parse(cleaned, NUMERIC_DATE);
        }
        return LocalDate.parse(cleaned, LONG_GERMAN_DATE);
    }
}
