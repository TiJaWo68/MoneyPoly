package de.in.mp.ui.energy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.binder.Binder;

import de.in.mp.energy.EnergyAsset;
import de.in.mp.energy.EnergyAssetService;
import de.in.mp.energy.Meter;
import de.in.mp.energy.MeterService;
import de.in.mp.energy.MeterType;

/**
 * Modal dialog for creating and editing a household or PV {@link Meter}.
 */
public class MeterEditorDialog extends Dialog {

    private final MeterService meterService;
    private final EnergyAssetService assetService;
    private final Binder<Meter> binder = new Binder<>(Meter.class);

    private final TextField name = new TextField();
    private final TextField serialNumber = new TextField();
    private final ComboBox<MeterType> type = new ComboBox<>();
    private final TextField unit = new TextField();
    private final BigDecimalField conversionFactor = new BigDecimalField();
    private final BigDecimalField overflowThreshold = new BigDecimalField();
    private final ComboBox<EnergyAsset> asset = new ComboBox<>();
    private final IntegerField displayOrder = new IntegerField();
    private final DatePicker installedAt = new DatePicker();
    private final TextField recognitionHint = new TextField();
    private final Checkbox active = new Checkbox();

    private final Button saveButton = new Button();
    private final Button cancelButton = new Button();

    private Meter currentMeter;
    private Runnable onSaved;

    public MeterEditorDialog(MeterService meterService, EnergyAssetService assetService) {
        this.meterService = meterService;
        this.assetService = assetService;

        configureFields();
        configureBinder();

        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveButton.addClickListener(event -> save());
        cancelButton.addClickListener(event -> close());

        FormLayout form = new FormLayout(
                name, serialNumber, type, unit, conversionFactor,
                overflowThreshold, asset, displayOrder, installedAt, recognitionHint, active);
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1));

        HorizontalLayout actions = new HorizontalLayout(cancelButton, saveButton);
        actions.setWidthFull();
        actions.setJustifyContentMode(JustifyContentMode.END);

        add(form, actions);
        setCloseOnEsc(true);
        setCloseOnOutsideClick(false);
    }

    public void openForCreate(Runnable onSaved) {
        this.onSaved = onSaved;
        this.currentMeter = new Meter();
        this.currentMeter.setType(MeterType.GRID_IMPORT);
        this.currentMeter.setUnit("kWh");
        this.currentMeter.setConversionFactor(BigDecimal.ONE);
        this.currentMeter.setDisplayOrder(100);
        this.currentMeter.setActive(true);
        setHeaderTitle(getTranslation("energy.meter.editor.title.new"));
        refreshAssets();
        binder.readBean(currentMeter);
        asset.clear();
        open();
    }

    public void openForEdit(Meter meter, Runnable onSaved) {
        this.onSaved = onSaved;
        this.currentMeter = meter;
        setHeaderTitle(getTranslation("energy.meter.editor.title.edit"));
        List<EnergyAsset> assets = refreshAssets();
        binder.readBean(meter);
        UUID assetId = meter.getAssetId();
        asset.setValue(assets.stream()
                .filter(a -> a.getId() != null && a.getId().equals(assetId))
                .findFirst()
                .orElse(null));
        open();
    }

    private void configureFields() {
        name.setLabel(getTranslation("energy.meter.editor.field.name"));
        serialNumber.setLabel(getTranslation("energy.meter.editor.field.serial"));
        type.setLabel(getTranslation("energy.meter.editor.field.type"));
        unit.setLabel(getTranslation("energy.meter.editor.field.unit"));
        conversionFactor.setLabel(getTranslation("energy.meter.editor.field.conversion"));
        overflowThreshold.setLabel(getTranslation("energy.meter.editor.field.overflow"));
        asset.setLabel(getTranslation("energy.meter.editor.field.asset"));
        displayOrder.setLabel(getTranslation("energy.meter.editor.field.order"));
        installedAt.setLabel(getTranslation("energy.meter.editor.field.installed-at"));
        recognitionHint.setLabel(getTranslation("energy.meter.editor.field.recognition-hint"));
        recognitionHint.setHelperText(getTranslation("energy.meter.editor.hint.recognition-hint"));
        active.setLabel(getTranslation("energy.meter.editor.field.active"));
        saveButton.setText(getTranslation("energy.button.save"));
        cancelButton.setText(getTranslation("energy.button.cancel"));

        type.setItems(MeterType.values());
        type.setItemLabelGenerator(t -> getTranslation("energy.meter.type." + t.name()));
        type.addValueChangeListener(event -> {
            if (event.getValue() != null) {
                unit.setValue(MeterService.defaultUnitFor(event.getValue()));
            }
        });

        asset.setClearButtonVisible(true);
        asset.setItemLabelGenerator(EnergyAsset::getName);
    }

    private void configureBinder() {
        binder.forField(name)
                .asRequired(getTranslation("energy.error.required"))
                .bind(Meter::getName, Meter::setName);
        binder.forField(serialNumber)
                .bind(Meter::getSerialNumber, Meter::setSerialNumber);
        binder.forField(type)
                .asRequired(getTranslation("energy.error.required"))
                .bind(Meter::getType, Meter::setType);
        binder.forField(unit)
                .bind(Meter::getUnit, Meter::setUnit);
        binder.forField(conversionFactor)
                .bind(Meter::getConversionFactor, Meter::setConversionFactor);
        binder.forField(overflowThreshold)
                .bind(Meter::getOverflowThreshold, Meter::setOverflowThreshold);
        binder.forField(displayOrder)
                .bind(Meter::getDisplayOrder, (m, v) -> m.setDisplayOrder(v != null ? v : 100));
        binder.forField(installedAt)
                .bind(Meter::getInstalledAt, Meter::setInstalledAt);
        binder.forField(recognitionHint)
                .bind(Meter::getRecognitionHint, Meter::setRecognitionHint);
        binder.forField(active)
                .bind(Meter::isActive, Meter::setActive);
    }

    private List<EnergyAsset> refreshAssets() {
        List<EnergyAsset> assets = assetService.findAll();
        asset.setItems(assets);
        return assets;
    }

    private void save() {
        if (!binder.writeBeanIfValid(currentMeter)) {
            return;
        }
        currentMeter.setAssetId(asset.getValue() != null ? asset.getValue().getId() : null);

        try {
            meterService.save(currentMeter);
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

