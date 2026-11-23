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
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.binder.Binder;

import de.in.mp.energy.EnergyAsset;
import de.in.mp.energy.EnergyAssetService;
import de.in.mp.energy.Meter;
import de.in.mp.energy.MeterReading;
import de.in.mp.energy.MeterReadingService;
import de.in.mp.energy.MeterService;
import de.in.mp.energy.MeterType;
import de.in.mp.energy.sync.EnergyDataSource;
import de.in.mp.energy.sync.EnergyDataSourceProvider;
import de.in.mp.energy.sync.EnergySyncService;
import de.in.mp.energy.sync.FusionSolarKioskClient;
import de.in.mp.energy.sync.ShellyPro3EmClient;

/**
 * Modal dialog for creating and editing a household or PV {@link Meter}.
 */
public class MeterEditorDialog extends Dialog {

    private final MeterService meterService;
    private final EnergyAssetService assetService;
    private final EnergySyncService energySyncService;
    private final MeterReadingService readingService;
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

    private final ComboBox<EnergyDataSource> dataSource = new ComboBox<>();
    private final ComboBox<String> sourceMetricKey = new ComboBox<>();
    private final BigDecimalField sourceOffset = new BigDecimalField();
    private final Button calibrateOffsetButton = new Button();
    private final HorizontalLayout offsetRow = new HorizontalLayout();
    private final TextField lastRemoteId = new TextField();
    private final Button syncRemoteIdButton = new Button();
    private final HorizontalLayout remoteIdRow = new HorizontalLayout();

    private final Button saveButton = new Button();
    private final Button cancelButton = new Button();

    private Meter currentMeter;
    private Runnable onSaved;

    public MeterEditorDialog(MeterService meterService, EnergyAssetService assetService) {
        this(meterService, assetService, null, null);
    }

    public MeterEditorDialog(
            MeterService meterService,
            EnergyAssetService assetService,
            EnergySyncService energySyncService,
            MeterReadingService readingService) {
        this.meterService = meterService;
        this.assetService = assetService;
        this.energySyncService = energySyncService;
        this.readingService = readingService;

        configureFields();
        configureBinder();

        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveButton.addClickListener(event -> save());
        cancelButton.addClickListener(event -> close());

        FormLayout form = new FormLayout(
                name, serialNumber, type, unit, conversionFactor,
                overflowThreshold, asset, displayOrder, installedAt, recognitionHint, active);
        if (energySyncService != null) {
            offsetRow.add(sourceOffset, calibrateOffsetButton);
            offsetRow.setDefaultVerticalComponentAlignment(Alignment.END);
            offsetRow.expand(sourceOffset);
            offsetRow.setWidthFull();

            remoteIdRow.add(lastRemoteId, syncRemoteIdButton);
            remoteIdRow.setDefaultVerticalComponentAlignment(Alignment.END);
            remoteIdRow.expand(lastRemoteId);
            remoteIdRow.setWidthFull();

            form.add(dataSource, sourceMetricKey, offsetRow, remoteIdRow);
        }
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1));

        HorizontalLayout actions = new HorizontalLayout(cancelButton, saveButton);
        actions.setWidthFull();
        actions.setJustifyContentMode(JustifyContentMode.END);

        add(form, actions);
        setCloseOnEsc(true);
        setCloseOnOutsideClick(false);
    }

    public void openForCreate(Runnable onSaved) {
        openForCreate(onSaved, null);
    }

    public void openForCreate(Runnable onSaved, UUID houseId) {
        this.onSaved = onSaved;
        this.currentMeter = new Meter();
        this.currentMeter.setHouseId(houseId);
        this.currentMeter.setType(MeterType.GRID_IMPORT);
        this.currentMeter.setUnit("kWh");
        this.currentMeter.setConversionFactor(BigDecimal.ONE);
        this.currentMeter.setDisplayOrder(100);
        this.currentMeter.setActive(true);
        setHeaderTitle(getTranslation("energy.meter.editor.title.new"));
        refreshAssets(houseId);
        binder.readBean(currentMeter);
        asset.clear();
        refreshDataSources(houseId, null);
        open();
    }

    public void openForEdit(Meter meter, Runnable onSaved) {
        this.onSaved = onSaved;
        this.currentMeter = meter;
        setHeaderTitle(getTranslation("energy.meter.editor.title.edit"));
        List<EnergyAsset> assets = refreshAssets(meter.getHouseId());
        binder.readBean(meter);
        UUID assetId = meter.getAssetId();
        asset.setValue(assets.stream()
                .filter(a -> a.getId() != null && a.getId().equals(assetId))
                .findFirst()
                .orElse(null));
        refreshDataSources(meter.getHouseId(), meter.getDataSourceId());
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

        dataSource.setLabel(getTranslation("energy.meter.editor.field.data-source"));
        dataSource.setClearButtonVisible(true);
        dataSource.setItemLabelGenerator(EnergyDataSource::getName);
        dataSource.addValueChangeListener(event -> updateDataSourceFields());

        sourceMetricKey.setLabel(getTranslation("energy.meter.editor.field.source-metric"));
        sourceMetricKey.setAllowCustomValue(true);
        sourceMetricKey.setClearButtonVisible(true);
        sourceMetricKey.addCustomValueSetListener(event -> sourceMetricKey.setValue(event.getDetail()));

        sourceOffset.setLabel(getTranslation("energy.meter.editor.field.source-offset"));
        calibrateOffsetButton.setText(getTranslation("energy.meter.editor.button.calibrate-offset"));
        calibrateOffsetButton.setIcon(VaadinIcon.MAGIC.create());
        calibrateOffsetButton.addClickListener(event -> calibrateOffset());

        lastRemoteId.setLabel(getTranslation("energy.meter.editor.field.last-remote-id"));
        syncRemoteIdButton.setText(getTranslation("energy.meter.editor.button.sync-remote-id"));
        syncRemoteIdButton.setIcon(VaadinIcon.REFRESH.create());
        syncRemoteIdButton.addClickListener(event -> fetchLatestRemoteId());
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

    private List<EnergyAsset> refreshAssets(UUID houseId) {
        List<EnergyAsset> assets = assetService.findByHouse(houseId);
        asset.setItems(assets);
        return assets;
    }

    private void refreshDataSources(UUID houseId, UUID selectedSourceId) {
        if (energySyncService == null) {
            return;
        }
        List<EnergyDataSource> sources = energySyncService.findByHouse(houseId);
        dataSource.setItems(sources);
        EnergyDataSource selected = sources.stream()
                .filter(s -> s.getId() != null && s.getId().equals(selectedSourceId))
                .findFirst()
                .orElse(null);
        dataSource.setValue(selected);
        updateDataSourceFields();
        sourceMetricKey.setValue(currentMeter.getSourceMetricKey());
        sourceOffset.setValue(currentMeter.getSourceOffset() != null ? currentMeter.getSourceOffset() : BigDecimal.ZERO);
        lastRemoteId.setValue(currentMeter.getLastRemoteId() != null
                ? String.valueOf(currentMeter.getLastRemoteId())
                : "");
    }

    private void updateDataSourceFields() {
        EnergyDataSource selected = dataSource.getValue();
        boolean hasSource = energySyncService != null && selected != null;
        sourceMetricKey.setVisible(hasSource);
        boolean isWallbox = hasSource && selected.getProvider() == EnergyDataSourceProvider.KATHREIN_WALLBOX;
        offsetRow.setVisible(hasSource && !isWallbox);
        remoteIdRow.setVisible(isWallbox);

        if (hasSource) {
            List<String> presets = switch (selected.getProvider()) {
                case SHELLY_PRO_3EM -> List.of(
                        ShellyPro3EmClient.METRIC_TOTAL_ACT,
                        ShellyPro3EmClient.METRIC_TOTAL_ACT_RET);
                case FUSIONSOLAR_KIOSK -> List.of(FusionSolarKioskClient.METRIC_CUMULATIVE_ENERGY);
                case KATHREIN_WALLBOX -> List.of();
            };
            String currentMetric = sourceMetricKey.getValue();
            sourceMetricKey.setItems(presets);
            if (currentMetric != null && !currentMetric.isBlank()) {
                sourceMetricKey.setValue(currentMetric);
            } else if (presets.size() == 1) {
                sourceMetricKey.setValue(presets.get(0));
            }
            sourceMetricKey.setHelperText(isWallbox
                    ? getTranslation("energy.meter.editor.hint.source-metric-rfid")
                    : "");
        }
    }

    private void calibrateOffset() {
        EnergyDataSource selected = dataSource.getValue();
        String metric = sourceMetricKey.getValue();
        if (selected == null || metric == null || metric.isBlank() || readingService == null
                || currentMeter.getId() == null) {
            Notification.show(getTranslation("energy.meter.editor.error.calibrate-missing"),
                    4000, Notification.Position.MIDDLE);
            return;
        }
        List<MeterReading> timeline = readingService.findByMeter(currentMeter.getId());
        if (timeline.isEmpty()) {
            Notification.show(getTranslation("energy.meter.editor.error.calibrate-no-reading"),
                    4000, Notification.Position.MIDDLE);
            return;
        }
        BigDecimal latest = timeline.get(timeline.size() - 1).getReadingValue();
        try {
            BigDecimal offset = energySyncService.calibrateOffset(selected.getId(), metric, latest);
            sourceOffset.setValue(offset);
            Notification.show(getTranslation("energy.meter.editor.notification.calibrated", offset.toPlainString()),
                    3500, Notification.Position.BOTTOM_START);
        } catch (Exception exception) {
            Notification.show(getTranslation("energy.notification.error", exception.getMessage()),
                    5000, Notification.Position.MIDDLE);
        }
    }

    private void fetchLatestRemoteId() {
        EnergyDataSource selected = dataSource.getValue();
        if (selected == null || energySyncService == null) {
            return;
        }
        try {
            long maxId = energySyncService.fetchLatestWallboxRemoteId(selected.getId());
            lastRemoteId.setValue(String.valueOf(maxId));
        } catch (Exception exception) {
            Notification.show(getTranslation("energy.notification.error", exception.getMessage()),
                    5000, Notification.Position.MIDDLE);
        }
    }

    private void save() {
        if (!binder.writeBeanIfValid(currentMeter)) {
            return;
        }
        currentMeter.setAssetId(asset.getValue() != null ? asset.getValue().getId() : null);
        if (energySyncService != null) {
            currentMeter.setDataSourceId(dataSource.getValue() != null ? dataSource.getValue().getId() : null);
            String metric = sourceMetricKey.getValue();
            currentMeter.setSourceMetricKey(metric == null || metric.isBlank() ? null : metric.trim());
            currentMeter.setSourceOffset(sourceOffset.getValue() != null ? sourceOffset.getValue() : BigDecimal.ZERO);
            String rawRemote = lastRemoteId.getValue();
            if (rawRemote != null && !rawRemote.isBlank()) {
                try {
                    currentMeter.setLastRemoteId(Long.parseLong(rawRemote.trim()));
                } catch (NumberFormatException ignored) {
                    Notification.show(getTranslation("energy.notification.error", "Invalid remote ID"),
                            4000, Notification.Position.MIDDLE);
                    return;
                }
            } else {
                currentMeter.setLastRemoteId(null);
            }
        }

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

