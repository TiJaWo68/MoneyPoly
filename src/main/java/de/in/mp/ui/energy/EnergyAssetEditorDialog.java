package de.in.mp.ui.energy;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.binder.Binder;

import de.in.mp.energy.EnergyAsset;
import de.in.mp.energy.EnergyAssetService;

/**
 * Modal dialog for creating and editing an {@link EnergyAsset}.
 */
public class EnergyAssetEditorDialog extends Dialog {

    private final EnergyAssetService assetService;
    private final Binder<EnergyAsset> binder = new Binder<>(EnergyAsset.class);

    private final TextField name = new TextField();
    private final DatePicker installationDate = new DatePicker();
    private final BigDecimalField investmentCost = new BigDecimalField();
    private final BigDecimalField initialAmortized = new BigDecimalField();
    private final TextArea notes = new TextArea();

    private final Button saveButton = new Button();
    private final Button cancelButton = new Button();

    private EnergyAsset currentAsset;
    private Runnable onSaved;

    public EnergyAssetEditorDialog(EnergyAssetService assetService) {
        this.assetService = assetService;

        configureFields();
        configureBinder();

        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveButton.addClickListener(event -> save());
        cancelButton.addClickListener(event -> close());

        FormLayout form = new FormLayout(name, installationDate, investmentCost, initialAmortized, notes);
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
        this.currentAsset = new EnergyAsset();
        this.currentAsset.setInstallationDate(LocalDate.now());
        this.currentAsset.setInitialAmortized(BigDecimal.ZERO);
        setHeaderTitle(getTranslation("energy.asset.editor.title.new"));
        binder.readBean(currentAsset);
        open();
    }

    public void openForEdit(EnergyAsset asset, Runnable onSaved) {
        this.onSaved = onSaved;
        this.currentAsset = asset;
        setHeaderTitle(getTranslation("energy.asset.editor.title.edit"));
        binder.readBean(asset);
        open();
    }

    private void configureFields() {
        name.setLabel(getTranslation("energy.asset.editor.field.name"));
        installationDate.setLabel(getTranslation("energy.asset.editor.field.installation-date"));
        investmentCost.setLabel(getTranslation("energy.asset.editor.field.investment-cost"));
        initialAmortized.setLabel(getTranslation("energy.asset.editor.field.initial-amortized"));
        notes.setLabel(getTranslation("energy.asset.editor.field.notes"));
        saveButton.setText(getTranslation("energy.button.save"));
        cancelButton.setText(getTranslation("energy.button.cancel"));
    }

    private void configureBinder() {
        binder.forField(name)
                .asRequired(getTranslation("energy.error.required"))
                .bind(EnergyAsset::getName, EnergyAsset::setName);
        binder.forField(installationDate)
                .asRequired(getTranslation("energy.error.required"))
                .bind(EnergyAsset::getInstallationDate, EnergyAsset::setInstallationDate);
        binder.forField(investmentCost)
                .asRequired(getTranslation("energy.error.required"))
                .bind(EnergyAsset::getInvestmentCost, EnergyAsset::setInvestmentCost);
        binder.forField(initialAmortized)
                .bind(EnergyAsset::getInitialAmortized, EnergyAsset::setInitialAmortized);
        binder.forField(notes)
                .bind(EnergyAsset::getNotes, EnergyAsset::setNotes);
    }

    private void save() {
        if (!binder.writeBeanIfValid(currentAsset)) {
            return;
        }
        try {
            assetService.save(currentAsset);
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

