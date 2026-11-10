package de.in.mp.ui.energy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.textfield.TextField;

import de.in.mp.energy.EnergyDateRow;
import de.in.mp.energy.Meter;
import de.in.mp.energy.MeterReading;
import de.in.mp.energy.MeterReadingService;
import de.in.mp.energy.MeterService;
import de.in.mp.energy.PlausibilityWarning;

/**
 * Spreadsheet-style batch entry dialog that allows entering or editing all
 * meter readings for a specific date in one compact form — just like adding a
 * new row in Excel/Calc.
 */
public class BatchMeterReadingDialog extends Dialog {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final MeterService meterService;
    private final MeterReadingService readingService;

    private final DatePicker readingDate = new DatePicker();
    private final TextField notes = new TextField();
    private final FormLayout meterFieldsLayout = new FormLayout();
    private final Map<UUID, BigDecimalField> fieldsByMeterId = new LinkedHashMap<>();

    private final Button saveButton = new Button();
    private final Button cancelButton = new Button();

    private Runnable onSaved;
    private boolean warningAcknowledged;

    public BatchMeterReadingDialog(MeterService meterService, MeterReadingService readingService) {
        this.meterService = meterService;
        this.readingService = readingService;

        readingDate.setLabel(getTranslation("energy.batch.field.date"));
        readingDate.addValueChangeListener(event -> {
            warningAcknowledged = false;
            updatePreviousHints();
        });

        notes.setLabel(getTranslation("energy.batch.field.notes"));
        notes.setWidthFull();

        meterFieldsLayout.setResponsiveSteps(
                new FormLayout.ResponsiveStep("0", 1),
                new FormLayout.ResponsiveStep("500px", 2));

        saveButton.setText(getTranslation("energy.batch.button.save"));
        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveButton.addClickListener(event -> save());

        cancelButton.setText(getTranslation("energy.batch.button.cancel"));
        cancelButton.addClickListener(event -> close());

        FormLayout headerForm = new FormLayout(readingDate, notes);
        headerForm.setResponsiveSteps(
                new FormLayout.ResponsiveStep("0", 1),
                new FormLayout.ResponsiveStep("500px", 2));

        HorizontalLayout actions = new HorizontalLayout(cancelButton, saveButton);
        actions.setWidthFull();
        actions.setJustifyContentMode(JustifyContentMode.END);

        setWidth("720px");
        add(headerForm, meterFieldsLayout, actions);
        setCloseOnEsc(true);
        setCloseOnOutsideClick(false);
    }

    public void openForCreate(Runnable onSaved) {
        this.onSaved = onSaved;
        this.warningAcknowledged = false;
        setHeaderTitle(getTranslation("energy.batch.title.new"));
        readingDate.setValue(LocalDate.now());
        notes.clear();
        rebuildMeterFields(Map.of());
        updatePreviousHints();
        open();
    }

    public void openForEdit(EnergyDateRow row, Runnable onSaved) {
        this.onSaved = onSaved;
        this.warningAcknowledged = false;
        setHeaderTitle(getTranslation("energy.batch.title.edit"));
        readingDate.setValue(row.readingDate());
        notes.setValue(row.notes() != null ? row.notes() : "");
        rebuildMeterFields(row.readingsByMeter());
        updatePreviousHints();
        open();
    }

    private void rebuildMeterFields(Map<UUID, MeterReading> existingByMeter) {
        meterFieldsLayout.removeAll();
        fieldsByMeterId.clear();

        List<Meter> activeMeters = meterService.findActive();
        for (Meter meter : activeMeters) {
            BigDecimalField field = new BigDecimalField(meter.getName() + " (" + meter.getUnit() + ")");
            field.setWidthFull();
            field.addValueChangeListener(e -> warningAcknowledged = false);

            MeterReading existing = existingByMeter.get(meter.getId());
            if (existing != null) {
                field.setValue(existing.getReadingValue());
            }

            fieldsByMeterId.put(meter.getId(), field);
            meterFieldsLayout.add(field);
        }
    }

    private void updatePreviousHints() {
        LocalDate date = readingDate.getValue();
        if (date == null) {
            return;
        }
        for (Map.Entry<UUID, BigDecimalField> entry : fieldsByMeterId.entrySet()) {
            UUID meterId = entry.getKey();
            BigDecimalField field = entry.getValue();
            List<MeterReading> history = readingService.findByMeter(meterId);

            MeterReading previous = null;
            for (MeterReading candidate : history) {
                if (candidate.getReadingDate().isBefore(date)) {
                    previous = candidate;
                }
            }

            if (previous != null) {
                field.setHelperText(getTranslation("energy.batch.field.previous-hint",
                        previous.getReadingValue().stripTrailingZeros().toPlainString(),
                        DATE_FORMAT.format(previous.getReadingDate())));
            } else {
                field.setHelperText(getTranslation("energy.batch.field.no-previous"));
            }
        }
    }

    private void save() {
        LocalDate date = readingDate.getValue();
        if (date == null) {
            Notification.show(getTranslation("energy.error.required"), 4000, Notification.Position.MIDDLE);
            return;
        }

        Map<UUID, BigDecimal> valuesToSave = new LinkedHashMap<>();
        for (Map.Entry<UUID, BigDecimalField> entry : fieldsByMeterId.entrySet()) {
            BigDecimal value = entry.getValue().getValue();
            if (value != null) {
                valuesToSave.put(entry.getKey(), value);
            }
        }

        if (!warningAcknowledged) {
            for (Map.Entry<UUID, BigDecimal> entry : valuesToSave.entrySet()) {
                Optional<PlausibilityWarning> warning = readingService.checkPlausibility(
                        entry.getKey(), date, entry.getValue());
                if (warning.isPresent()) {
                    PlausibilityWarning w = warning.get();
                    warningAcknowledged = true;
                    Notification notification = Notification.show(
                            getTranslation("energy.batch.warning.plausibility",
                                    w.meterName(),
                                    w.enteredValue().stripTrailingZeros().toPlainString(),
                                    w.previousValue().stripTrailingZeros().toPlainString(),
                                    DATE_FORMAT.format(w.previousDate())),
                            6000,
                            Notification.Position.MIDDLE);
                    notification.addThemeVariants(NotificationVariant.LUMO_WARNING);
                    return;
                }
            }
        }

        try {
            readingService.recordBatchReadings(date, valuesToSave, notes.getValue());
            Notification.show(getTranslation("energy.notification.saved"), 3000, Notification.Position.BOTTOM_START);
            close();
            if (onSaved != null) {
                onSaved.run();
            }
        } catch (IllegalArgumentException exception) {
            Notification.show(getTranslation("energy.notification.error", exception.getMessage()),
                    5000, Notification.Position.MIDDLE);
        }
    }
}

