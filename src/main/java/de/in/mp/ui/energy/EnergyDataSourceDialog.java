package de.in.mp.ui.energy;

import java.util.UUID;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.theme.lumo.LumoUtility;

import de.in.mp.energy.sync.EnergyDataSource;
import de.in.mp.energy.sync.EnergyDataSourceProvider;
import de.in.mp.energy.sync.EnergySyncService;

/**
 * Modal editor for configuring an automated energy device/cloud data source
 * (Shelly Pro 3EM, Huawei FusionSolar Kiosk, Kathrein easyOperate Wallbox).
 */
public class EnergyDataSourceDialog extends Dialog {

    private final EnergySyncService syncService;

    private final TextField name = new TextField();
    private final ComboBox<EnergyDataSourceProvider> provider = new ComboBox<>();
    private final TextField baseUrl = new TextField();
    private final TextField username = new TextField();
    private final PasswordField secret = new PasswordField();
    private final Checkbox trustSelfSigned = new Checkbox();
    private final Checkbox enabled = new Checkbox();
    private final Checkbox autoSyncEnabled = new Checkbox();
    private final Span testResult = new Span();

    private final Button testButton = new Button();
    private final Button saveButton = new Button();
    private final Button cancelButton = new Button();

    private EnergyDataSource currentSource;
    private Runnable onSaved;

    public EnergyDataSourceDialog(EnergySyncService syncService) {
        this.syncService = syncService;
        configureFields();

        FormLayout form = new FormLayout(
                name, provider, baseUrl, username, secret, trustSelfSigned, enabled, autoSyncEnabled, testResult);
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1));

        HorizontalLayout actions = new HorizontalLayout(testButton, cancelButton, saveButton);
        actions.setWidthFull();
        actions.setJustifyContentMode(JustifyContentMode.END);
        actions.expand(testButton);

        add(form, actions);
        setWidth("520px");
        setMaxWidth("95vw");
        setCloseOnEsc(true);
        setCloseOnOutsideClick(false);
    }

    public void openForCreate(UUID houseId, Runnable onSaved) {
        this.onSaved = onSaved;
        this.currentSource = new EnergyDataSource();
        this.currentSource.setHouseId(houseId);
        this.currentSource.setProvider(EnergyDataSourceProvider.SHELLY_PRO_3EM);
        this.currentSource.setEnabled(true);
        this.currentSource.setAutoSyncEnabled(true);
        setHeaderTitle(getTranslation("energy.source.dialog.title.new"));
        populateFields(true);
        open();
    }

    public void openForEdit(EnergyDataSource source, Runnable onSaved) {
        this.onSaved = onSaved;
        this.currentSource = source;
        setHeaderTitle(getTranslation("energy.source.dialog.title.edit"));
        populateFields(false);
        open();
    }

    private void configureFields() {
        name.setLabel(getTranslation("energy.source.field.name"));
        provider.setLabel(getTranslation("energy.source.field.provider"));
        provider.setItems(EnergyDataSourceProvider.values());
        provider.setItemLabelGenerator(p -> getTranslation("energy.source.provider." + p.name()));
        provider.addValueChangeListener(event -> {
            updateProviderSpecificVisibility();
            if (event.isFromClient() && event.getValue() != null) {
                applyProviderDefaults(event.getValue());
            }
        });

        baseUrl.setLabel(getTranslation("energy.source.field.base-url"));
        username.setLabel(getTranslation("energy.source.field.username"));
        secret.setLabel(getTranslation("energy.source.field.secret"));
        trustSelfSigned.setLabel(getTranslation("energy.source.field.trust-self-signed"));
        enabled.setLabel(getTranslation("energy.source.field.enabled"));
        autoSyncEnabled.setLabel(getTranslation("energy.source.field.auto-sync"));
        testResult.addClassNames(LumoUtility.FontSize.SMALL);

        testButton.setText(getTranslation("energy.source.button.test"));
        testButton.setIcon(VaadinIcon.PLUG.create());
        testButton.addClickListener(event -> runConnectionTest());

        saveButton.setText(getTranslation("energy.button.save"));
        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveButton.addClickListener(event -> save());

        cancelButton.setText(getTranslation("energy.button.cancel"));
        cancelButton.addClickListener(event -> close());
    }

    private void populateFields(boolean isNew) {
        name.setValue(currentSource.getName() != null ? currentSource.getName() : "");
        provider.setValue(currentSource.getProvider() != null
                ? currentSource.getProvider()
                : EnergyDataSourceProvider.SHELLY_PRO_3EM);
        baseUrl.setValue(currentSource.getBaseUrl() != null ? currentSource.getBaseUrl() : "");
        username.setValue(currentSource.getUsername() != null ? currentSource.getUsername() : "");
        secret.clear();
        secret.setHelperText(isNew ? "" : getTranslation("energy.source.hint.secret-keep"));
        trustSelfSigned.setValue(currentSource.isTrustSelfSigned());
        enabled.setValue(currentSource.isEnabled());
        autoSyncEnabled.setValue(currentSource.isAutoSyncEnabled());
        testResult.setText("");
        testResult.setVisible(false);
        updateProviderSpecificVisibility();
        if (isNew && baseUrl.getValue().isBlank()) {
            applyProviderDefaults(provider.getValue());
        }
    }

    private void applyProviderDefaults(EnergyDataSourceProvider selected) {
        if (selected == null) {
            return;
        }
        switch (selected) {
            case SHELLY_PRO_3EM -> {
                if (name.getValue().isBlank()) {
                    name.setValue("Shelly Pro 3EM");
                }
                baseUrl.setValue("http://192.168.178.132");
                username.clear();
                trustSelfSigned.setValue(false);
            }
            case FUSIONSOLAR_KIOSK -> {
                if (name.getValue().isBlank()) {
                    name.setValue("FusionSolar Kiosk");
                }
                baseUrl.setValue("https://uni003eu5.fusionsolar.huawei.com");
                username.clear();
                trustSelfSigned.setValue(false);
            }
            case HUAWEI_MODBUS -> {
                if (name.getValue().isBlank()) {
                    name.setValue("Huawei PV (Modbus)");
                }
                baseUrl.setValue("homeassistant.local:502");
                if (username.getValue().isBlank()) {
                    username.setValue("1,2,16");
                }
                trustSelfSigned.setValue(false);
            }
            case KATHREIN_WALLBOX -> {
                if (name.getValue().isBlank()) {
                    name.setValue("Kathrein Wallbox");
                }
                baseUrl.setValue("https://192.168.178.181");
                if (username.getValue().isBlank()) {
                    username.setValue("operator");
                }
                trustSelfSigned.setValue(true);
            }
        }
    }

    private void updateProviderSpecificVisibility() {
        EnergyDataSourceProvider p = provider.getValue();
        username.setVisible(p != null && p.supportsUsername());
        secret.setVisible(p != null && p.usesSecret());
        trustSelfSigned.setVisible(p != null && p.supportsSelfSigned());
        if (p == EnergyDataSourceProvider.HUAWEI_MODBUS) {
            baseUrl.setLabel(getTranslation("energy.source.field.modbus-host"));
            username.setLabel(getTranslation("energy.source.field.unit-ids"));
            username.setHelperText(getTranslation("energy.source.hint.unit-ids"));
        } else {
            baseUrl.setLabel(getTranslation("energy.source.field.base-url"));
            username.setLabel(getTranslation("energy.source.field.username"));
            username.setHelperText("");
        }
        if (p == EnergyDataSourceProvider.FUSIONSOLAR_KIOSK) {
            secret.setLabel(getTranslation("energy.source.field.kiosk-key"));
        } else {
            secret.setLabel(getTranslation("energy.source.field.secret"));
        }
    }

    private void runConnectionTest() {
        EnergyDataSource candidate = buildCandidateCopy();
        testResult.removeClassNames(LumoUtility.TextColor.ERROR, LumoUtility.TextColor.SUCCESS);
        testResult.setVisible(true);
        try {
            String result = syncService.testConnection(candidate, secret.getValue());
            testResult.setText(result);
            testResult.addClassNames(LumoUtility.TextColor.SUCCESS);
        } catch (Exception exception) {
            testResult.setText(getTranslation("energy.source.test.failed", exception.getMessage()));
            testResult.addClassNames(LumoUtility.TextColor.ERROR);
        }
    }

    private void save() {
        applyFieldsTo(currentSource);
        try {
            syncService.saveDataSource(currentSource, secret.getValue());
            Notification.show(getTranslation("energy.notification.saved"), 3000, Notification.Position.BOTTOM_START);
            close();
            if (onSaved != null) {
                onSaved.run();
            }
        } catch (RuntimeException exception) {
            Notification.show(getTranslation("energy.notification.error", exception.getMessage()),
                    5000, Notification.Position.MIDDLE);
        }
    }

    private EnergyDataSource buildCandidateCopy() {
        EnergyDataSource copy = new EnergyDataSource();
        copy.setId(currentSource.getId());
        copy.setHouseId(currentSource.getHouseId());
        copy.setSecretEncrypted(currentSource.getSecretEncrypted());
        applyFieldsTo(copy);
        return copy;
    }

    private void applyFieldsTo(EnergyDataSource target) {
        target.setName(name.getValue());
        target.setProvider(provider.getValue());
        target.setBaseUrl(baseUrl.getValue());
        target.setUsername(username.getValue());
        target.setTrustSelfSigned(Boolean.TRUE.equals(trustSelfSigned.getValue()));
        target.setEnabled(Boolean.TRUE.equals(enabled.getValue()));
        target.setAutoSyncEnabled(Boolean.TRUE.equals(autoSyncEnabled.getValue()));
    }
}
