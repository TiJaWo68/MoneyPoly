package de.in.mp.energy.bill;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Generates polite, structured objection and correction email drafts based on
 * the findings of {@link UtilityBillVerificationResult}.
 */
public final class UtilityBillComplaintGenerator {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final BigDecimal SIGNIFICANT_DELTA_KWH = new BigDecimal("5");
    private static final BigDecimal SIGNIFICANT_INSTALLMENT_DIFF = new BigDecimal("5");

    private UtilityBillComplaintGenerator() {
    }

    /**
     * Optional user inputs enriching the generated complaint draft.
     */
    public record ComplaintOptions(
            BigDecimal currentReading,
            LocalDate currentReadingDate,
            boolean smartMeterInstalled,
            String senderName) {

        public static ComplaintOptions empty() {
            return new ComplaintOptions(null, null, false, null);
        }
    }

    /**
     * Generated email subject and body ready to be copied to clipboard.
     */
    public record ComplaintDraft(String subject, String body) {
    }

    /**
     * Generates a localized (German or English) complaint draft for the verification result.
     */
    public static ComplaintDraft generate(
            UtilityBillVerificationResult result,
            ComplaintOptions options,
            Locale locale) {
        if (result == null || result.bill() == null) {
            return new ComplaintDraft("", "");
        }
        Locale effectiveLocale = locale != null ? locale : Locale.GERMANY;
        ComplaintOptions opts = options != null ? options : ComplaintOptions.empty();
        boolean german = !"en".equalsIgnoreCase(effectiveLocale.getLanguage());
        return german
                ? buildGermanDraft(result, opts, effectiveLocale)
                : buildEnglishDraft(result, opts, effectiveLocale);
    }

    private static ComplaintDraft buildGermanDraft(
            UtilityBillVerificationResult result, ComplaintOptions opts, Locale locale) {
        UtilityBill bill = result.bill();
        String invoiceRef = nullToPlaceholder(bill.invoiceNumber(), "[Rechnungsnummer]");
        String customerSuffix = hasText(bill.customerNumber()) ? ", Kunden-Nr. " + bill.customerNumber() : "";
        String meterSuffix = hasText(bill.meterSerialNumber()) ? ", Zähler " + bill.meterSerialNumber() : "";

        String subject = "Bitte um Prüfung und Korrektur der Abrechnung " + invoiceRef + customerSuffix + meterSuffix;

        StringBuilder sb = new StringBuilder();
        sb.append("Sehr geehrte Damen und Herren,\n\n");
        sb.append("vielen Dank für die Übersendung Ihrer Abrechnung vom ")
                .append(formatDate(bill.invoiceDate(), "[Datum]"))
                .append(" (Rechnungsnummer ").append(invoiceRef);
        if (hasText(bill.customerNumber())) {
            sb.append(", Kunden-Nr. ").append(bill.customerNumber());
        }
        sb.append(") für den Zeitraum ")
                .append(formatDate(bill.periodStart(), "[Start]"))
                .append(" – ")
                .append(formatDate(bill.periodEnd(), "[Ende]"))
                .append(".\n\n");

        sb.append("Beim Abgleich mit unseren laufend dokumentierten Zählerständen");
        if (hasText(bill.meterSerialNumber())) {
            sb.append(" (Zählernummer ").append(bill.meterSerialNumber()).append(")");
        }
        sb.append(" ist mir aufgefallen, dass die Abrechnung in folgenden Punkten nicht mit unseren Messwerten übereinstimmt:\n\n");

        appendGermanFindings(sb, result, opts, locale);

        if (opts.smartMeterInstalled()) {
            sb.append("\nDa an der Messstelle ein intelligentes Messsystem (iMSys) installiert ist, bitte ich Sie außerdem freundlich zu prüfen, warum zum Stichtag ")
                    .append(formatDate(bill.periodEnd(), "[Stichtag]"))
                    .append(" kein abgelesener Messwert vorgelegen hat.\n");
        }

        sb.append("\nIch möchte Sie daher höflich bitten:\n");
        int step = 1;
        if (result.recordedClosingReading() != null) {
            sb.append(step++).append(". die Abrechnung ").append(invoiceRef)
                    .append(" auf Basis des tatsächlichen Zählerstands von ")
                    .append(formatKwh(result.recordedClosingReading(), locale))
                    .append(" zum ").append(formatDate(bill.periodEnd(), "[Stichtag]"))
                    .append(" zu korrigieren,\n");
        } else {
            sb.append(step++).append(". die Abrechnung ").append(invoiceRef)
                    .append(" anhand der beigefügten Messwerte zu überprüfen und entsprechend zu korrigieren,\n");
        }

        InstallmentEvaluation installment = result.installmentEvaluation();
        if (installment != null
                && installment.monthlyDifferenceEur() != null
                && installment.monthlyDifferenceEur().compareTo(SIGNIFICANT_INSTALLMENT_DIFF) > 0) {
            sb.append(step++).append(". den künftigen monatlichen Abschlag (derzeit angesetzt mit ")
                    .append(formatEur(installment.billedNewInstallmentGrossEur(), locale))
                    .append(") entsprechend dem tatsächlichen Verbrauch auf rund ")
                    .append(formatEur(installment.recommendedInstallmentGrossEur(), locale))
                    .append(" anzupassen,\n");
        }

        if (bill.amountDueGrossEur() != null && bill.amountDueGrossEur().signum() > 0) {
            sb.append(step).append(". einen geplanten Bankeinzug (")
                    .append(formatEur(bill.amountDueGrossEur(), locale))
                    .append(") bis zur Klärung anzupassen bzw. auszusetzen. Sollte der Einzug technisch bereits veranlasst sein, erfolgt eine Zahlung des strittigen Mehrbetrags ausdrücklich unter Vorbehalt der Rückforderung nach Rechnungskorrektur.\n");
        } else {
            sb.append(step).append(". das sich ergebende Guthaben entsprechend der korrigierten Endabrechnung zu berücksichtigen.\n");
        }

        sb.append("\nEin aktuelles Foto des Zählers mit erkennbarer Zählernummer lege ich dieser Nachricht bei. ");
        sb.append("Vielen Dank vorab für Ihre Mühe und eine kurze Rückmeldung.\n\n");
        sb.append("Mit freundlichen Grüßen\n");
        sb.append(nullToPlaceholder(opts.senderName(), "[Ihr Name]"));

        return new ComplaintDraft(subject, sb.toString());
    }

    private static void appendGermanFindings(
            StringBuilder sb, UtilityBillVerificationResult result, ComplaintOptions opts, Locale locale) {
        List<PeriodComparison> deviantEstimated = findDeviantEstimatedPeriods(result);
        for (PeriodComparison period : deviantEstimated) {
            sb.append("- Im Teilzeitraum ")
                    .append(formatDate(period.from(), "-")).append(" – ").append(formatDate(period.to(), "-"))
                    .append(" wurde ein geschätzter Endstand von ")
                    .append(formatKwh(period.billedEndReading(), locale))
                    .append(" (").append(formatKwh(period.billedConsumptionKwh(), locale)).append(" Verbrauch) angesetzt. ")
                    .append("Unser erfasster Zählerstand zum ").append(formatDate(period.to(), "-"))
                    .append(" beträgt jedoch ").append(formatKwh(period.recordedEndReading(), locale))
                    .append(" (").append(formatKwh(period.recordedConsumptionKwh(), locale)).append(" Verbrauch) – eine Abweichung von ")
                    .append(formatSignedKwh(period.deltaKwh(), locale));
            if (period.deltaGrossEur() != null) {
                sb.append(" bzw. rund ").append(formatEur(period.deltaGrossEur().abs(), locale)).append(" brutto");
            }
            sb.append(".\n");
        }

        if (deviantEstimated.isEmpty()
                && result.closingReadingDeltaKwh() != null
                && result.closingReadingDeltaKwh().abs().compareTo(SIGNIFICANT_DELTA_KWH) > 0) {
            UtilityBill bill = result.bill();
            BigDecimal billedClosing = bill.periods().isEmpty()
                    ? null
                    : bill.periods().get(bill.periods().size() - 1).newReading();
            sb.append("- Der abgerechnete Endstand von ")
                    .append(formatKwh(billedClosing, locale))
                    .append(" weicht von unserem abgelesenen Zählerstand am ")
                    .append(formatDate(bill.periodEnd(), "-"))
                    .append(" (").append(formatKwh(result.recordedClosingReading(), locale))
                    .append(") um ").append(formatSignedKwh(result.closingReadingDeltaKwh(), locale))
                    .append(" ab.\n");
        }

        if (result.totalDeltaKwh() != null && result.totalDeltaKwh().abs().compareTo(SIGNIFICANT_DELTA_KWH) > 0) {
            sb.append("- Über den gesamten Vergleichszeitraum ergibt sich eine Verbrauchsabweichung von ")
                    .append(formatSignedKwh(result.totalDeltaKwh(), locale));
            if (result.totalDeltaGrossEur() != null) {
                sb.append(" (ca. ").append(formatEur(result.totalDeltaGrossEur().abs(), locale)).append(" brutto)");
            }
            sb.append(".\n");
        }

        if (result.arithmeticCheck() != null && !result.arithmeticCheck().allOk()) {
            sb.append("- Zudem ergab die rechnerische Prüfung der Einzelpositionen bzw. Summen eine Differenz von ")
                    .append(formatEur(result.arithmeticCheck().maxLineDiffEur(), locale))
                    .append(".\n");
        }

        if (opts.currentReading() != null) {
            sb.append("- Unser aktueller Zählerstand");
            if (opts.currentReadingDate() != null) {
                sb.append(" vom ").append(formatDate(opts.currentReadingDate(), ""));
            }
            sb.append(" beträgt ").append(formatKwh(opts.currentReading(), locale)).append(".\n");
        }
    }

    private static ComplaintDraft buildEnglishDraft(
            UtilityBillVerificationResult result, ComplaintOptions opts, Locale locale) {
        UtilityBill bill = result.bill();
        String invoiceRef = nullToPlaceholder(bill.invoiceNumber(), "[Invoice Number]");
        String customerSuffix = hasText(bill.customerNumber()) ? ", Customer No. " + bill.customerNumber() : "";
        String meterSuffix = hasText(bill.meterSerialNumber()) ? ", Meter " + bill.meterSerialNumber() : "";

        String subject = "Request for review and correction of utility bill " + invoiceRef + customerSuffix + meterSuffix;

        StringBuilder sb = new StringBuilder();
        sb.append("Dear Sir or Madam,\n\n");
        sb.append("Thank you for sending your utility bill dated ")
                .append(formatDate(bill.invoiceDate(), "[Date]"))
                .append(" (Invoice No. ").append(invoiceRef);
        if (hasText(bill.customerNumber())) {
            sb.append(", Customer No. ").append(bill.customerNumber());
        }
        sb.append(") covering the billing period ")
                .append(formatDate(bill.periodStart(), "[Start]"))
                .append(" – ")
                .append(formatDate(bill.periodEnd(), "[End]"))
                .append(".\n\n");

        sb.append("When comparing the invoice against our documented meter readings");
        if (hasText(bill.meterSerialNumber())) {
            sb.append(" (Meter ").append(bill.meterSerialNumber()).append(")");
        }
        sb.append(", I noticed the following discrepancies:\n\n");

        for (PeriodComparison period : findDeviantEstimatedPeriods(result)) {
            sb.append("- For ").append(formatDate(period.from(), "-")).append(" – ").append(formatDate(period.to(), "-"))
                    .append(", the invoice uses an estimated closing reading of ")
                    .append(formatKwh(period.billedEndReading(), locale))
                    .append(" (").append(formatKwh(period.billedConsumptionKwh(), locale)).append("), whereas our recorded reading on ")
                    .append(formatDate(period.to(), "-")).append(" was ")
                    .append(formatKwh(period.recordedEndReading(), locale))
                    .append(" (").append(formatKwh(period.recordedConsumptionKwh(), locale)).append("), resulting in an excess of ")
                    .append(formatSignedKwh(period.deltaKwh(), locale));
            if (period.deltaGrossEur() != null) {
                sb.append(" (~").append(formatEur(period.deltaGrossEur().abs(), locale)).append(" gross)");
            }
            sb.append(".\n");
        }

        if (result.totalDeltaKwh() != null && result.totalDeltaKwh().abs().compareTo(SIGNIFICANT_DELTA_KWH) > 0) {
            sb.append("- Across the compared billing period, total billed consumption differs by ")
                    .append(formatSignedKwh(result.totalDeltaKwh(), locale));
            if (result.totalDeltaGrossEur() != null) {
                sb.append(" (~").append(formatEur(result.totalDeltaGrossEur().abs(), locale)).append(" gross)");
            }
            sb.append(".\n");
        }

        if (result.arithmeticCheck() != null && !result.arithmeticCheck().allOk()) {
            sb.append("- In addition, a line-item arithmetic check detected a discrepancy of ")
                    .append(formatEur(result.arithmeticCheck().maxLineDiffEur(), locale)).append(".\n");
        }

        if (opts.currentReading() != null) {
            sb.append("- Our current meter reading");
            if (opts.currentReadingDate() != null) {
                sb.append(" on ").append(formatDate(opts.currentReadingDate(), ""));
            }
            sb.append(" is ").append(formatKwh(opts.currentReading(), locale)).append(".\n");
        }

        if (opts.smartMeterInstalled()) {
            sb.append("\nSince a smart meter gateway (iMSys) is installed at this metering point, please also check why no actual reading was transmitted as of ")
                    .append(formatDate(bill.periodEnd(), "[Period End]"))
                    .append(".\n");
        }

        sb.append("\nCould you please:\n");
        int step = 1;
        if (result.recordedClosingReading() != null) {
            sb.append(step++).append(". issue a corrected invoice for ").append(invoiceRef)
                    .append(" based on the actual reading of ")
                    .append(formatKwh(result.recordedClosingReading(), locale))
                    .append(" on ").append(formatDate(bill.periodEnd(), "[Period End]")).append(",\n");
        } else {
            sb.append(step++).append(". review and correct invoice ").append(invoiceRef)
                    .append(" based on the attached meter readings,\n");
        }

        InstallmentEvaluation installment = result.installmentEvaluation();
        if (installment != null
                && installment.monthlyDifferenceEur() != null
                && installment.monthlyDifferenceEur().compareTo(SIGNIFICANT_INSTALLMENT_DIFF) > 0) {
            sb.append(step++).append(". adjust the new monthly installment (currently ")
                    .append(formatEur(installment.billedNewInstallmentGrossEur(), locale))
                    .append(") in line with actual consumption to approx. ")
                    .append(formatEur(installment.recommendedInstallmentGrossEur(), locale)).append(",\n");
        }

        if (bill.amountDueGrossEur() != null && bill.amountDueGrossEur().signum() > 0) {
            sb.append(step).append(". pause or adjust the upcoming direct debit (")
                    .append(formatEur(bill.amountDueGrossEur(), locale))
                    .append(") accordingly; should it already be processed, payment of the disputed excess is made strictly under reservation of reclaim.\n");
        } else {
            sb.append(step).append(". update the resulting credit balance in the corrected statement.\n");
        }

        sb.append("\nA photo of the meter clearly showing the serial number is attached. Thank you very much in advance for your review and a brief reply.\n\n");
        sb.append("Kind regards,\n");
        sb.append(nullToPlaceholder(opts.senderName(), "[Your Name]"));

        return new ComplaintDraft(subject, sb.toString());
    }

    private static List<PeriodComparison> findDeviantEstimatedPeriods(UtilityBillVerificationResult result) {
        return result.periodComparisons().stream()
                .filter(p -> p.readingSource() == UtilityBillReadingSource.ESTIMATED
                        && p.deltaKwh() != null
                        && p.deltaKwh().abs().compareTo(SIGNIFICANT_DELTA_KWH) > 0)
                .toList();
    }

    private static String formatDate(LocalDate date, String fallback) {
        return date == null ? fallback : DATE_FORMAT.format(date);
    }

    private static String formatKwh(BigDecimal val, Locale locale) {
        if (val == null) {
            return "-";
        }
        NumberFormat fmt = NumberFormat.getNumberInstance(locale);
        fmt.setMaximumFractionDigits(0);
        fmt.setMinimumFractionDigits(0);
        return fmt.format(val.setScale(0, RoundingMode.HALF_UP)) + " kWh";
    }

    private static String formatSignedKwh(BigDecimal val, Locale locale) {
        if (val == null) {
            return "-";
        }
        String sign = val.signum() > 0 ? "+" : "";
        return sign + formatKwh(val, locale);
    }

    private static String formatEur(BigDecimal val, Locale locale) {
        if (val == null) {
            return "-";
        }
        NumberFormat fmt = NumberFormat.getNumberInstance(locale);
        fmt.setMinimumFractionDigits(2);
        fmt.setMaximumFractionDigits(2);
        return fmt.format(val.setScale(2, RoundingMode.HALF_UP)) + " €";
    }

    /**
     * Builds a Gmail Web compose URL pre-filled with recipient, subject, and body.
     */
    public static String buildGmailComposeUrl(String toEmail, ComplaintDraft draft) {
        StringBuilder url = new StringBuilder("https://mail.google.com/mail/?view=cm&fs=1");
        if (hasText(toEmail)) {
            url.append("&to=").append(encodeUrlComponent(toEmail.trim()));
        }
        if (draft != null && hasText(draft.subject())) {
            url.append("&su=").append(encodeUrlComponent(draft.subject()));
        }
        if (draft != null && hasText(draft.body())) {
            url.append("&body=").append(encodeUrlComponent(draft.body()));
        }
        return url.toString();
    }

    /**
     * Builds a standard {@code mailto:} URI pre-filled with recipient, subject, and body.
     */
    public static String buildMailtoUrl(String toEmail, ComplaintDraft draft) {
        String recipient = hasText(toEmail) ? encodeUrlComponent(toEmail.trim()) : "";
        StringBuilder url = new StringBuilder("mailto:").append(recipient);
        String sep = "?";
        if (draft != null && hasText(draft.subject())) {
            url.append(sep).append("subject=").append(encodeUrlComponent(draft.subject()));
            sep = "&";
        }
        if (draft != null && hasText(draft.body())) {
            url.append(sep).append("body=").append(encodeUrlComponent(draft.body()));
        }
        return url.toString();
    }

    private static String encodeUrlComponent(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String nullToPlaceholder(String s, String placeholder) {
        return hasText(s) ? s.trim() : placeholder;
    }
}
