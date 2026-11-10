package de.in.mp.ui.energy;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.binder.Binder;

import de.in.mp.energy.EnergyTariff;
import de.in.mp.energy.EnergyTariffService;
import de.in.mp.energy.MeterType;

/**
 * Modal dialog for creating and editing an {@link EnergyTariff}.
 */
public class EnergyTariffEditorDialog extends Dialog {

    private final EnergyTariffService tariffService;
    private final Binder<EnergyTariff> binder = new Binder<>(EnergyTariff.class);

    private final TextField name = new TextField();
    private final ComboBox<MeterType> forMeterType = new ComboBox<>();
    private final DatePicker validFrom = new DatePicker();
    private final DatePicker validTo = new DatePicker();
    private final BigDecimalField unitPriceEur = new BigDecimalField();
    private final BigDecimalField monthlyBaseFeeEur = new BigDecimalField();

    private final Button saveButton = new Button();
    private final Button cancelButton = new Button();

    private EnergyTariff currentTariff;
    private Runnable onSaved;

    public EnergyTariffEditorDialog(EnergyTariffService tariffService) {
        this.tariffService = tariffService;

        configureFields();
        configureBinder();

        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveButton.addClickListener(event -> save());
        cancelButton.addClickListener(event -> close());

        FormLayout form = new FormLayout(name, forMeterType, validFrom, validTo, unitPriceEur, monthlyBaseFeeEur);
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
        this.currentTariff = new EnergyTariff();
        this.currentTariff.setForMeterType(MeterType.GRID_IMPORT);
        this.currentTariff.setValidFrom(LocalDate.now());
        this.currentTariff.setMonthlyBaseFeeEur(BigDecimal.ZERO);
        setHeaderTitle(getTranslation("energy.tariff.editor.title.new"));
        binder.readBean(currentTariff);
        open();
    }

    public void openForEdit(EnergyTariff tariff, Runnable onSaved) {
        this.onSaved = onSaved;
        this.currentTariff = tariff;
        setHeaderTitle(getTranslation("energy.tariff.editor.title.edit"));
        binder.readBean(tariff);
        open();
    }

    private void configureFields() {
        name.setLabel(getTranslation("energy.tariff.editor.field.name"));
        forMeterType.setLabel(getTranslation("energy.tariff.editor.field.type"));
        validFrom.setLabel(getTranslation("energy.tariff.editor.field.valid-from"));
        validTo.setLabel(getTranslation("energy.tariff.editor.field.valid-to"));
        unitPriceEur.setLabel(getTranslation("energy.tariff.editor.field.unit-price"));
        monthlyBaseFeeEur.setLabel(getTranslation("energy.tariff.editor.field.base-fee"));
        saveButton.setText(getTranslation("energy.button.save"));
        cancelButton.setText(getTranslation("energy.button.cancel"));

        forMeterType.setItems(MeterType.values());
        forMeterType.setItemLabelGenerator(t -> getTranslation("energy.meter.type." + t.name()));
    }

    private void configureBinder() {
        binder.forField(name)
                .asRequired(getTranslation("energy.error.required"))
                .bind(EnergyTariff::getName, EnergyTariff::setName);
        binder.forField(forMeterType)
                .asRequired(getTranslation("energy.error.required"))
                .bind(EnergyTariff::getForMeterType, EnergyTariff::setForMeterType);
        binder.forField(validFrom)
                .asRequired(getTranslation("energy.error.required"))
                .bind(EnergyTariff::getValidFrom, EnergyTariff::setValidFrom);
        binder.forField(validTo)
                .bind(EnergyTariff::getValidTo, EnergyTariff::setValidTo);
        binder.forField(unitPriceEur)
                .asRequired(getTranslation("energy.error.required"))
                .bind(EnergyTariff::getUnitPriceEur, EnergyTariff::setUnitPriceEur);
        binder.forField(monthlyBaseFeeEur)
                .bind(EnergyTariff::getMonthlyBaseFeeEur, EnergyTariff::setMonthlyBaseFeeEur);
    }

    private void save() {
        if (!binder.writeBeanIfValid(currentTariff)) {
            return;
        }
        try {
            tariffService.save(currentTariff);
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

