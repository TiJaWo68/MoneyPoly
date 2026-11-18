package de.in.mp.ui.energy;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.theme.lumo.LumoUtility;

import de.in.mp.energy.Meter;
import de.in.mp.energy.MeterService;
import de.in.mp.energy.bill.ArithmeticCheckResult;
import de.in.mp.energy.bill.BillVerdict;
import de.in.mp.energy.bill.InstallmentEvaluation;
import de.in.mp.energy.bill.PeriodComparison;
import de.in.mp.energy.bill.PeriodMatchQuality;
import de.in.mp.energy.bill.TariffComparison;
import de.in.mp.energy.bill.UtilityBill;
import de.in.mp.energy.bill.UtilityBillComplaintGenerator;
import de.in.mp.energy.bill.UtilityBillComplaintGenerator.ComplaintDraft;
import de.in.mp.energy.bill.UtilityBillComplaintGenerator.ComplaintOptions;
import de.in.mp.energy.bill.UtilityBillReadingSource;
import de.in.mp.energy.bill.UtilityBillVerificationResult;
import de.in.mp.energy.bill.UtilityBillVerificationService;
import de.in.mp.i18n.DateFormatter;
import de.in.mp.i18n.MoneyFormatter;

/**
 * Dialog for uploading a utility bill PDF, matching it against recorded MoneyPoly
 * meter readings and tariffs, checking bill arithmetic, and evaluating installments.
 */
public class UtilityBillCheckDialog extends Dialog {

    private static final String PDF_MIME_TYPE = "application/pdf";

    private final UtilityBillVerificationService verificationService;
    private final MeterService meterService;

    private final Upload upload;
    private final ComboBox<Meter> meterSelector = new ComboBox<>();
    private final Button saveSerialButton = new Button();
    private final Button toggleComplaintButton = new Button();

    private final VerticalLayout resultsContainer = new VerticalLayout();
    private final Div verdictBanner = new Div();
    private final HorizontalLayout summaryRow = new HorizontalLayout();
    private final VerticalLayout checksList = new VerticalLayout();
    private final Grid<PeriodComparison> periodGrid = new Grid<>(PeriodComparison.class, false);
    private final VerticalLayout complaintSection = new VerticalLayout();
    private final BigDecimalField currentReadingField = new BigDecimalField();
    private final DatePicker currentReadingDateField = new DatePicker();
    private final Checkbox smartMeterCheckbox = new Checkbox();
    private final TextField senderNameField = new TextField();
    private final TextField complaintSubjectField = new TextField();
    private final TextArea complaintBodyArea = new TextArea();
    private final Span complaintRecipientSpan = new Span();
    private final Anchor gmailLink = new Anchor();
    private final Anchor mailtoLink = new Anchor();

    private ByteArrayOutputStream pendingBuffer;
    private UUID currentHouseId;
    private UtilityBill currentBill;
    private UtilityBillVerificationResult lastResult;
    private List<Meter> availableMeters = List.of();
    private boolean updatingMeterSelector;
    private Runnable onMeterUpdated;

    public UtilityBillCheckDialog(UtilityBillVerificationService verificationService, MeterService meterService) {
        this.verificationService = verificationService;
        this.meterService = meterService;

        setHeaderTitle(getTranslation("energy.bill.dialog.title"));
        setWidth("1140px");
        setCloseOnEsc(true);
        setCloseOnOutsideClick(false);

        upload = new Upload((fileName, mimeType) -> {
            pendingBuffer = new ByteArrayOutputStream();
            return pendingBuffer;
        });
        upload.setAcceptedFileTypes(".pdf", PDF_MIME_TYPE);
        upload.setMaxFiles(1);
        upload.setUploadButton(new Button(getTranslation("energy.bill.upload.button"), VaadinIcon.UPLOAD.create()));
        upload.setDropLabel(new Span(getTranslation("energy.bill.upload.drop")));
        upload.setWidthFull();
        upload.addSucceededListener(event -> handleUploadedPdf());

        meterSelector.setLabel(getTranslation("energy.bill.field.meter"));
        meterSelector.setPlaceholder(getTranslation("energy.bill.meter.placeholder"));
        meterSelector.setItemLabelGenerator(this::formatMeterLabel);
        meterSelector.setClearButtonVisible(true);
        meterSelector.setWidth("380px");
        meterSelector.addValueChangeListener(event -> {
            if (!updatingMeterSelector && currentBill != null) {
                reverifyWithSelectedMeter();
            }
        });

        saveSerialButton.setText(getTranslation("energy.bill.button.save-serial"));
        saveSerialButton.setTooltipText(getTranslation("energy.bill.button.save-serial.tooltip"));
        saveSerialButton.setIcon(VaadinIcon.LINK.create());
        saveSerialButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        saveSerialButton.setVisible(false);
        saveSerialButton.addClickListener(event -> saveSerialToMeter());

        toggleComplaintButton.setText(getTranslation("energy.bill.complaint.button.open"));
        toggleComplaintButton.setIcon(VaadinIcon.ENVELOPE_O.create());
        toggleComplaintButton.addThemeVariants(ButtonVariant.LUMO_SMALL);
        toggleComplaintButton.setVisible(false);
        toggleComplaintButton.addClickListener(event -> toggleComplaintSection());

        HorizontalLayout meterRow = new HorizontalLayout(meterSelector, saveSerialButton, toggleComplaintButton);
        meterRow.setWidthFull();
        meterRow.setAlignItems(Alignment.END);

        configurePeriodGrid();
        configureComplaintSection();

        verdictBanner.setWidthFull();
        verdictBanner.addClassNames(LumoUtility.Padding.MEDIUM, LumoUtility.BorderRadius.MEDIUM);

        summaryRow.setWidthFull();
        summaryRow.setSpacing(true);
        summaryRow.getStyle().set("flex-wrap", "wrap");

        checksList.setPadding(false);
        checksList.setSpacing(false);
        checksList.addClassNames(LumoUtility.Gap.XSMALL);

        resultsContainer.setPadding(false);
        resultsContainer.setSpacing(true);
        resultsContainer.add(verdictBanner, summaryRow, checksList, periodGrid, complaintSection);
        resultsContainer.setVisible(false);

        Button closeButton = new Button(getTranslation("energy.bill.button.close"), event -> close());
        HorizontalLayout footer = new HorizontalLayout(closeButton);
        footer.setWidthFull();
        footer.setJustifyContentMode(JustifyContentMode.END);

        Paragraph intro = new Paragraph(getTranslation("energy.bill.dialog.description"));
        intro.addClassNames(LumoUtility.Margin.NONE, LumoUtility.TextColor.SECONDARY, LumoUtility.FontSize.SMALL);

        VerticalLayout content = new VerticalLayout(intro, upload, meterRow, resultsContainer, footer);
        content.setPadding(false);
        content.setSpacing(true);
        add(content);
    }

    /**
     * Opens the dialog scoped to the specified house.
     */
    public void openForCheck(UUID houseId, Runnable onMeterUpdated) {
        this.currentHouseId = houseId;
        this.onMeterUpdated = onMeterUpdated;
        this.currentBill = null;
        this.lastResult = null;
        this.pendingBuffer = null;

        availableMeters = houseId != null ? meterService.findByHouse(houseId) : meterService.findAll();
        updatingMeterSelector = true;
        meterSelector.setItems(availableMeters);
        meterSelector.clear();
        meterSelector.setHelperText(null);
        updatingMeterSelector = false;

        saveSerialButton.setVisible(false);
        toggleComplaintButton.setVisible(false);
        complaintSection.setVisible(false);
        currentReadingField.clear();
        currentReadingDateField.setValue(LocalDate.now());
        smartMeterCheckbox.setValue(false);
        resultsContainer.setVisible(false);
        periodGrid.setItems(List.of());
        upload.clearFileList();
        open();
    }

    private void handleUploadedPdf() {
        if (pendingBuffer == null) {
            return;
        }
        try {
            UtilityBillVerificationResult initial = verificationService.verifyPdf(
                    pendingBuffer.toByteArray(), currentHouseId, null);
            this.currentBill = initial.bill();

            Meter selected = resolveTargetMeter(initial);
            updatingMeterSelector = true;
            meterSelector.setValue(selected);
            updatingMeterSelector = false;

            UtilityBillVerificationResult effective = initial;
            if (selected != null && !Objects.equals(selected.getId(), initial.matchedMeterId())) {
                effective = verificationService.verifyBill(currentBill, currentHouseId, selected.getId());
            }
            renderResult(effective);
        } catch (IllegalArgumentException exception) {
            resultsContainer.setVisible(false);
            Notification notification = Notification.show(
                    getTranslation("energy.bill.error.unsupported", exception.getMessage()),
                    6000, Notification.Position.MIDDLE);
            notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }

    private Meter resolveTargetMeter(UtilityBillVerificationResult initial) {
        if (initial.matchedMeterId() == null) {
            return null;
        }
        return availableMeters.stream()
                .filter(m -> Objects.equals(m.getId(), initial.matchedMeterId()))
                .findFirst()
                .orElse(null);
    }

    private void reverifyWithSelectedMeter() {
        Meter selected = meterSelector.getValue();
        UUID meterId = selected != null ? selected.getId() : null;
        UtilityBillVerificationResult result = verificationService.verifyBill(
                currentBill, currentHouseId, meterId);
        renderResult(result);
    }

    private void saveSerialToMeter() {
        Meter selected = meterSelector.getValue();
        if (selected == null || currentBill == null || currentBill.meterSerialNumber() == null) {
            return;
        }
        selected.setSerialNumber(currentBill.meterSerialNumber());
        Meter saved = meterService.save(selected);
        availableMeters = currentHouseId != null ? meterService.findByHouse(currentHouseId) : meterService.findAll();

        updatingMeterSelector = true;
        meterSelector.setItems(availableMeters);
        availableMeters.stream()
                .filter(m -> Objects.equals(m.getId(), saved.getId()))
                .findFirst()
                .ifPresent(meterSelector::setValue);
        updatingMeterSelector = false;

        if (onMeterUpdated != null) {
            onMeterUpdated.run();
        }
        Notification.show(getTranslation("energy.bill.notification.serial-saved",
                saved.getSerialNumber(), saved.getName()), 4000, Notification.Position.BOTTOM_END)
                .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        reverifyWithSelectedMeter();
    }

    private void renderResult(UtilityBillVerificationResult result) {
        this.lastResult = result;
        UtilityBill bill = result.bill();
        Meter selectedMeter = meterSelector.getValue();
        boolean canSaveSerial = selectedMeter != null
                && bill.meterSerialNumber() != null && !bill.meterSerialNumber().isBlank()
                && !Objects.equals(selectedMeter.getSerialNumber(), bill.meterSerialNumber());
        saveSerialButton.setVisible(canSaveSerial);
        toggleComplaintButton.setVisible(true);

        if (selectedMeter == null && bill.meterSerialNumber() != null && !bill.meterSerialNumber().isBlank()) {
            meterSelector.setHelperText(getTranslation("energy.bill.meter.unmatched-helper", bill.meterSerialNumber()));
        } else {
            meterSelector.setHelperText(null);
        }

        renderVerdictBanner(result);
        renderSummaryCards(result);
        renderCheckLines(result);
        periodGrid.setItems(result.periodComparisons());
        if (complaintSection.isVisible()) {
            refreshComplaintDraft();
        }
        resultsContainer.setVisible(true);
    }

    private void renderVerdictBanner(UtilityBillVerificationResult result) {
        verdictBanner.removeAll();
        verdictBanner.getStyle().clear();

        BillVerdict verdict = result.verdict();
        String bg;
        String border;
        String titleKey;
        if (verdict == BillVerdict.PLAUSIBLE) {
            bg = "var(--lumo-success-color-10pct)";
            border = "var(--lumo-success-color)";
            titleKey = "energy.bill.verdict.plausible";
        } else if (verdict == BillVerdict.WARNING) {
            bg = "var(--lumo-warning-color-10pct, rgba(255, 193, 7, 0.14))";
            border = "var(--lumo-warning-color, #e6a100)";
            titleKey = "energy.bill.verdict.warning";
        } else {
            bg = "var(--lumo-error-color-10pct)";
            border = "var(--lumo-error-color)";
            titleKey = "energy.bill.verdict.discrepancy";
        }
        verdictBanner.getStyle()
                .set("background", bg)
                .set("border-left", "4px solid " + border);

        Span headline = new Span(getTranslation(titleKey));
        headline.addClassNames(LumoUtility.FontWeight.BOLD, LumoUtility.FontSize.MEDIUM);

        UtilityBill bill = result.bill();
        String meta = "%s · %s · %s – %s (%s) · %s".formatted(
                nullSafe(bill.providerName()),
                nullSafe(bill.invoiceNumber()),
                formatDate(bill.periodStart()),
                formatDate(bill.periodEnd()),
                bill.billedDays() != null ? getTranslation("energy.bill.days", bill.billedDays()) : "-",
                bill.tariffName() != null ? bill.tariffName() : "-");
        Span details = new Span(meta);
        details.addClassNames(LumoUtility.FontSize.SMALL, LumoUtility.TextColor.SECONDARY);

        VerticalLayout box = new VerticalLayout(headline, details);
        box.setPadding(false);
        box.setSpacing(false);
        verdictBanner.add(box);
    }

    private void renderSummaryCards(UtilityBillVerificationResult result) {
        summaryRow.removeAll();
        UtilityBill bill = result.bill();

        summaryRow.add(createCard(
                getTranslation("energy.bill.card.billed-consumption"),
                formatKwh(bill.totalConsumptionKwh()),
                getTranslation("energy.bill.card.gross-due",
                        formatMoney(bill.totalGrossEur()), formatMoney(bill.amountDueGrossEur()))));

        String actualVal = result.recordedTotalConsumptionKwh() != null
                ? formatKwh(result.recordedTotalConsumptionKwh())
                : getTranslation("energy.bill.no-data");
        String actualSub = result.closingReadingDeltaKwh() != null
                ? getTranslation("energy.bill.card.closing-reading",
                        formatDecimal(result.recordedClosingReading(), 0),
                        formatSignedKwh(result.closingReadingDeltaKwh()))
                : "";
        summaryRow.add(createCard(getTranslation("energy.bill.card.recorded-consumption"), actualVal, actualSub));

        String deltaVal = result.totalDeltaKwh() != null
                ? formatSignedKwh(result.totalDeltaKwh())
                : "-";
        String deltaSub = result.totalDeltaGrossEur() != null
                ? getTranslation("energy.bill.card.delta-eur",
                        formatSignedMoney(result.totalDeltaGrossEur()),
                        formatSignedMoney(result.totalDeltaNetEur()))
                : "";
        summaryRow.add(createCard(getTranslation("energy.bill.card.deviation"), deltaVal, deltaSub));

        InstallmentEvaluation eval = result.installmentEvaluation();
        if (eval != null) {
            summaryRow.add(createCard(
                    getTranslation("energy.bill.card.installment"),
                    formatMoney(eval.billedNewInstallmentGrossEur()),
                    getTranslation("energy.bill.card.installment-recommended",
                            formatMoney(eval.recommendedInstallmentGrossEur()),
                            formatSignedMoney(eval.monthlyDifferenceEur()))));
        }
    }

    private void renderCheckLines(UtilityBillVerificationResult result) {
        checksList.removeAll();
        ArithmeticCheckResult math = result.arithmeticCheck();
        checksList.add(createCheckBadge(
                math.allOk(),
                math.allOk()
                        ? getTranslation("energy.bill.check.arithmetic.ok")
                        : getTranslation("energy.bill.check.arithmetic.fail",
                                formatMoney(math.maxLineDiffEur()))));

        TariffComparison tariff = result.tariffComparison();
        if (tariff != null) {
            if (tariff.tariffFoundInApp()) {
                checksList.add(createCheckBadge(
                        tariff.matchesBillGrossOrNet(),
                        getTranslation(
                                tariff.matchesBillGrossOrNet()
                                        ? "energy.bill.check.tariff.ok"
                                        : "energy.bill.check.tariff.diff",
                                tariff.appTariffName(),
                                formatPrice(tariff.appUnitPriceEurPerKwh()),
                                formatPrice(tariff.billWeightedApNetEurPerKwh()),
                                formatPrice(tariff.billWeightedApGrossEurPerKwh()))));
            } else {
                checksList.add(createInfoBadge(getTranslation("energy.bill.check.tariff.none",
                        formatPrice(tariff.billWeightedApNetEurPerKwh()),
                        formatPrice(tariff.billWeightedApGrossEurPerKwh()))));
            }
        }

        if (result.estimatedPeriodCount() > 0) {
            checksList.add(createInfoBadge(
                    getTranslation("energy.bill.check.estimated-periods", result.estimatedPeriodCount())));
        }
    }

    private void configurePeriodGrid() {
        periodGrid.addColumn(row -> formatDate(row.from()) + " – " + formatDate(row.to()))
                .setHeader(getTranslation("energy.bill.column.period"))
                .setAutoWidth(true).setFlexGrow(1);
        periodGrid.addColumn(new ComponentRenderer<>(this::renderSourceBadge))
                .setHeader(getTranslation("energy.bill.column.source"))
                .setAutoWidth(true).setFlexGrow(0);
        periodGrid.addColumn(row -> formatDecimal(row.billedStartReading(), 0)
                        + " → " + formatDecimal(row.billedEndReading(), 0))
                .setHeader(getTranslation("energy.bill.column.billed-readings"))
                .setTextAlign(ColumnTextAlign.END).setAutoWidth(true);
        periodGrid.addColumn(row -> formatKwh(row.billedConsumptionKwh()))
                .setHeader(getTranslation("energy.bill.column.billed-kwh"))
                .setTextAlign(ColumnTextAlign.END).setAutoWidth(true);
        periodGrid.addColumn(this::formatRecordedRange)
                .setHeader(getTranslation("energy.bill.column.recorded-readings"))
                .setTextAlign(ColumnTextAlign.END).setAutoWidth(true);
        periodGrid.addColumn(row -> formatRecordedConsumption(row))
                .setHeader(getTranslation("energy.bill.column.recorded-kwh"))
                .setTextAlign(ColumnTextAlign.END).setAutoWidth(true);
        periodGrid.addColumn(new ComponentRenderer<>(this::renderDeltaBadge))
                .setHeader(getTranslation("energy.bill.column.delta-kwh"))
                .setTextAlign(ColumnTextAlign.END).setAutoWidth(true);
        periodGrid.addColumn(row -> row.deltaGrossEur() != null ? formatSignedMoney(row.deltaGrossEur()) : "-")
                .setHeader(getTranslation("energy.bill.column.delta-eur"))
                .setTextAlign(ColumnTextAlign.END).setAutoWidth(true);
        periodGrid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_COMPACT);
        periodGrid.setAllRowsVisible(true);
    }

    private Span renderSourceBadge(PeriodComparison row) {
        String key = switch (row.readingSource()) {
            case METER_READ -> "energy.bill.source.read";
            case ESTIMATED -> "energy.bill.source.estimated";
            case UNKNOWN -> "energy.bill.source.unknown";
        };
        String label = getTranslation(key);
        if (row.sourceNote() != null && !row.sourceNote().isBlank()) {
            label = label + " (" + row.sourceNote() + ")";
        }
        Span badge = new Span(label);
        badge.getElement().getThemeList().add("badge small");
        if (row.readingSource() == UtilityBillReadingSource.ESTIMATED) {
            badge.getElement().getThemeList().add("contrast");
        } else if (row.readingSource() == UtilityBillReadingSource.METER_READ) {
            badge.getElement().getThemeList().add("success");
        }
        return badge;
    }

    private Span renderDeltaBadge(PeriodComparison row) {
        if (row.deltaKwh() == null) {
            return new Span("-");
        }
        Span span = new Span(formatSignedKwh(row.deltaKwh()));
        BigDecimal abs = row.deltaKwh().abs();
        if (abs.compareTo(new BigDecimal("20")) > 0) {
            span.getStyle().set("color", "var(--lumo-error-text-color)").set("font-weight", "600");
        } else if (abs.compareTo(new BigDecimal("5")) > 0) {
            span.getStyle().set("color", "var(--lumo-warning-text-color, #b37400)").set("font-weight", "600");
        } else {
            span.getStyle().set("color", "var(--lumo-success-text-color)");
        }
        return span;
    }

    private Div createCard(String label, String value, String sublabel) {
        Div card = new Div();
        card.addClassNames(LumoUtility.Padding.SMALL, LumoUtility.BorderRadius.MEDIUM, LumoUtility.Background.CONTRAST_5);
        card.getStyle().set("min-width", "210px").set("flex", "1 1 210px");

        Span title = new Span(label);
        title.addClassNames(LumoUtility.FontSize.XSMALL, LumoUtility.TextColor.SECONDARY, LumoUtility.Display.BLOCK);

        Span val = new Span(value);
        val.addClassNames(LumoUtility.FontSize.LARGE, LumoUtility.FontWeight.BOLD, LumoUtility.Display.BLOCK);

        card.add(title, val);
        if (sublabel != null && !sublabel.isBlank()) {
            Span sub = new Span(sublabel);
            sub.addClassNames(LumoUtility.FontSize.XSMALL, LumoUtility.TextColor.SECONDARY, LumoUtility.Display.BLOCK);
            card.add(sub);
        }
        return card;
    }

    private Span createCheckBadge(boolean ok, String text) {
        Span span = new Span((ok ? "✓ " : "✕ ") + text);
        span.addClassNames(LumoUtility.FontSize.SMALL);
        span.getStyle().set("color", ok ? "var(--lumo-success-text-color)" : "var(--lumo-error-text-color)");
        return span;
    }

    private Span createInfoBadge(String text) {
        Span span = new Span("• " + text);
        span.addClassNames(LumoUtility.FontSize.SMALL, LumoUtility.TextColor.SECONDARY);
        return span;
    }

    private String formatMeterLabel(Meter meter) {
        if (meter == null) {
            return "";
        }
        if (meter.getSerialNumber() != null && !meter.getSerialNumber().isBlank()) {
            return meter.getName() + " (" + meter.getSerialNumber() + ")";
        }
        return meter.getName();
    }

    private String formatRecordedRange(PeriodComparison row) {
        if (row.matchQuality() == PeriodMatchQuality.NO_DATA) {
            return getTranslation("energy.bill.no-data");
        }
        String prefix = row.matchQuality() == PeriodMatchQuality.INTERPOLATED ? "≈ " : "";
        return prefix + formatDecimal(row.recordedStartReading(), 0)
                + " → " + formatDecimal(row.recordedEndReading(), 0);
    }

    private String formatRecordedConsumption(PeriodComparison row) {
        if (row.recordedConsumptionKwh() == null) {
            return "-";
        }
        String prefix = row.matchQuality() == PeriodMatchQuality.INTERPOLATED ? "≈ " : "";
        return prefix + formatKwh(row.recordedConsumptionKwh());
    }

    private String formatKwh(BigDecimal val) {
        if (val == null) {
            return "-";
        }
        return formatDecimal(val, 0) + " kWh";
    }

    private String formatSignedKwh(BigDecimal val) {
        if (val == null) {
            return "-";
        }
        String sign = val.signum() > 0 ? "+" : "";
        return sign + formatDecimal(val, 0) + " kWh";
    }

    private String formatDecimal(BigDecimal val, int maxFractionDigits) {
        if (val == null) {
            return "-";
        }
        NumberFormat fmt = NumberFormat.getNumberInstance(locale());
        fmt.setMaximumFractionDigits(maxFractionDigits);
        fmt.setMinimumFractionDigits(0);
        return fmt.format(val.setScale(maxFractionDigits, RoundingMode.HALF_UP));
    }

    private String formatPrice(BigDecimal val) {
        if (val == null) {
            return "-";
        }
        NumberFormat fmt = NumberFormat.getNumberInstance(locale());
        fmt.setMinimumFractionDigits(4);
        fmt.setMaximumFractionDigits(4);
        return fmt.format(val) + " €/kWh";
    }

    private String formatMoney(BigDecimal val) {
        return val == null ? "-" : MoneyFormatter.format(val, locale());
    }

    private String formatSignedMoney(BigDecimal val) {
        if (val == null) {
            return "-";
        }
        return (val.signum() > 0 ? "+" : "") + MoneyFormatter.format(val, locale());
    }

    private String formatDate(java.time.LocalDate date) {
        return date == null ? "-" : DateFormatter.format(date, locale());
    }

    private void configureComplaintSection() {
        complaintSection.setPadding(true);
        complaintSection.setSpacing(true);
        complaintSection.setWidthFull();
        complaintSection.addClassNames(LumoUtility.Background.CONTRAST_5, LumoUtility.BorderRadius.MEDIUM);
        complaintSection.setVisible(false);

        currentReadingField.setLabel(getTranslation("energy.bill.complaint.field.current-reading"));
        currentReadingField.setPlaceholder("kWh");
        currentReadingField.setWidth("170px");
        currentReadingField.addValueChangeListener(e -> refreshComplaintDraft());

        currentReadingDateField.setLabel(getTranslation("energy.bill.complaint.field.current-date"));
        currentReadingDateField.setValue(LocalDate.now());
        currentReadingDateField.setWidth("160px");
        currentReadingDateField.addValueChangeListener(e -> refreshComplaintDraft());

        senderNameField.setLabel(getTranslation("energy.bill.complaint.field.sender"));
        senderNameField.setWidth("220px");
        senderNameField.addValueChangeListener(e -> refreshComplaintDraft());

        smartMeterCheckbox.setLabel(getTranslation("energy.bill.complaint.field.imsys"));
        smartMeterCheckbox.addValueChangeListener(e -> refreshComplaintDraft());

        HorizontalLayout optionsRow = new HorizontalLayout(
                currentReadingField, currentReadingDateField, senderNameField, smartMeterCheckbox);
        optionsRow.setWidthFull();
        optionsRow.setAlignItems(Alignment.END);
        optionsRow.getStyle().set("flex-wrap", "wrap");

        complaintSubjectField.setLabel(getTranslation("energy.bill.complaint.field.subject"));
        complaintSubjectField.setWidthFull();
        complaintSubjectField.setValueChangeMode(ValueChangeMode.LAZY);
        complaintSubjectField.addValueChangeListener(e -> updateMailLinks());

        Button copySubjectButton = new Button(
                getTranslation("energy.bill.complaint.button.copy-subject"),
                VaadinIcon.COPY.create());
        copySubjectButton.addThemeVariants(ButtonVariant.LUMO_SMALL);
        bindClipboardCopy(copySubjectButton, complaintSubjectField.getElement());

        HorizontalLayout subjectRow = new HorizontalLayout(complaintSubjectField, copySubjectButton);
        subjectRow.setWidthFull();
        subjectRow.setAlignItems(Alignment.END);
        subjectRow.expand(complaintSubjectField);

        complaintBodyArea.setLabel(getTranslation("energy.bill.complaint.field.body"));
        complaintBodyArea.setWidthFull();
        complaintBodyArea.setMinHeight("240px");
        complaintBodyArea.setValueChangeMode(ValueChangeMode.LAZY);
        complaintBodyArea.addValueChangeListener(e -> updateMailLinks());

        Button copyBodyButton = new Button(
                getTranslation("energy.bill.complaint.button.copy-body"),
                VaadinIcon.COPY.create());
        copyBodyButton.addThemeVariants(ButtonVariant.LUMO_SMALL);
        bindClipboardCopy(copyBodyButton, complaintBodyArea.getElement());

        Button gmailButton = new Button(
                getTranslation("energy.bill.complaint.button.gmail"),
                VaadinIcon.EXTERNAL_LINK.create());
        gmailButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
        gmailLink.add(gmailButton);
        gmailLink.setTarget("_blank");
        gmailLink.getElement().setAttribute("rel", "noopener noreferrer");
        gmailLink.getStyle().set("text-decoration", "none");

        Button mailtoButton = new Button(
                getTranslation("energy.bill.complaint.button.mailto"),
                VaadinIcon.ENVELOPE_O.create());
        mailtoButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        mailtoLink.add(mailtoButton);
        mailtoLink.getStyle().set("text-decoration", "none");

        complaintRecipientSpan.addClassNames(LumoUtility.FontSize.SMALL, LumoUtility.TextColor.SECONDARY);

        HorizontalLayout linkButtons = new HorizontalLayout(copyBodyButton, mailtoLink, gmailLink);
        linkButtons.setAlignItems(Alignment.CENTER);
        linkButtons.getStyle().set("flex-wrap", "wrap");

        HorizontalLayout bodyActions = new HorizontalLayout(complaintRecipientSpan, linkButtons);
        bodyActions.setWidthFull();
        bodyActions.setAlignItems(Alignment.CENTER);
        bodyActions.setJustifyContentMode(JustifyContentMode.BETWEEN);
        bodyActions.getStyle().set("flex-wrap", "wrap");

        complaintSection.add(optionsRow, subjectRow, complaintBodyArea, bodyActions);
    }

    private void toggleComplaintSection() {
        boolean show = !complaintSection.isVisible();
        complaintSection.setVisible(show);
        if (show) {
            refreshComplaintDraft();
        }
    }

    private void refreshComplaintDraft() {
        if (lastResult == null) {
            return;
        }
        ComplaintOptions options = new ComplaintOptions(
                currentReadingField.getValue(),
                currentReadingDateField.getValue(),
                Boolean.TRUE.equals(smartMeterCheckbox.getValue()),
                senderNameField.getValue());
        ComplaintDraft draft = UtilityBillComplaintGenerator.generate(lastResult, options, locale());
        complaintSubjectField.setValue(draft.subject());
        complaintBodyArea.setValue(draft.body());
        String recipient = lastResult.bill() != null ? lastResult.bill().providerEmail() : null;
        complaintRecipientSpan.setText(recipient != null && !recipient.isBlank()
                ? getTranslation("energy.bill.complaint.recipient", recipient)
                : "");
        updateMailLinks();
    }

    private void updateMailLinks() {
        String recipient = lastResult != null && lastResult.bill() != null
                ? lastResult.bill().providerEmail()
                : null;
        ComplaintDraft currentDraft = new ComplaintDraft(
                complaintSubjectField.getValue(),
                complaintBodyArea.getValue());
        gmailLink.setHref(UtilityBillComplaintGenerator.buildGmailComposeUrl(recipient, currentDraft));
        mailtoLink.setHref(UtilityBillComplaintGenerator.buildMailtoUrl(recipient, currentDraft));
    }

    private void bindClipboardCopy(Button button, Element sourceFieldElement) {
        button.getElement().executeJs(
                """
                this.addEventListener('click', () => {
                  const dialog = $0;
                  const field = $1;
                  const text = (field ? (field.value ?? (field.inputElement ? field.inputElement.value : '')) : '') || '';
                  const fallbackCopy = () => {
                    const ta = document.createElement('textarea');
                    ta.value = text;
                    ta.setAttribute('readonly', '');
                    ta.style.position = 'fixed';
                    ta.style.top = '0';
                    ta.style.left = '0';
                    ta.style.opacity = '0';
                    const host = (field && field.parentElement) ? field.parentElement : this.parentElement;
                    host.appendChild(ta);
                    ta.focus();
                    ta.select();
                    ta.setSelectionRange(0, text.length);
                    let ok = false;
                    try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
                    ta.remove();
                    return ok;
                  };
                  if (navigator.clipboard && window.isSecureContext) {
                    navigator.clipboard.writeText(text).then(
                      () => dialog.$server.onClipboardCopyResult(true),
                      () => dialog.$server.onClipboardCopyResult(fallbackCopy())
                    );
                  } else {
                    dialog.$server.onClipboardCopyResult(fallbackCopy());
                  }
                });
                """,
                getElement(),
                sourceFieldElement);
    }

    @ClientCallable
    private void onClipboardCopyResult(boolean success) {
        if (success) {
            Notification.show(getTranslation("energy.bill.complaint.copied"), 3000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        } else {
            Notification.show(getTranslation("energy.bill.complaint.copy-failed"), 5000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_WARNING);
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static Locale locale() {
        return UI.getCurrent() != null ? UI.getCurrent().getLocale() : Locale.GERMANY;
    }
}
