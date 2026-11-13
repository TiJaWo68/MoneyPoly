package de.in.mp.ui.admin;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.theme.lumo.LumoUtility;

import de.in.mp.admin.AppSettingService;
import de.in.mp.energy.vision.VisionConfig;
import de.in.mp.energy.vision.VisionMeterClient;
import de.in.mp.energy.vision.VisionPreset;

/**
 * Administration tab for configuring application-wide settings and thresholds.
 */
public class SettingsTab extends VerticalLayout implements AdminTab {

    private final AppSettingService appSettingService;
    private final VisionMeterClient visionMeterClient;

    final BigDecimalField savingsInterestThreshold = new BigDecimalField();
    final ComboBox<VisionPreset> visionPreset = new ComboBox<>();
    final TextField visionBaseUrl = new TextField();
    final ComboBox<String> visionModel = new ComboBox<>();
    final Button fetchModelsButton = new Button();
    final PasswordField visionApiKey = new PasswordField();
    final Button testVisionButton = new Button();
    final Button saveButton = new Button();
    private final HorizontalLayout modelRow = new HorizontalLayout();
    private List<String> availableModels = defaultPresetModels();

    public SettingsTab(AppSettingService appSettingService) {
        this(appSettingService, null);
    }

    @Autowired
    public SettingsTab(AppSettingService appSettingService, VisionMeterClient visionMeterClient) {
        this.appSettingService = appSettingService;
        this.visionMeterClient = visionMeterClient;

        setSizeFull();
        setPadding(false);
        setSpacing(false);

        add(createHeader(), createForm());
    }

    @Override
    public String getLabelKey() {
        return "admin.tab.settings";
    }

    @Override
    public Component asComponent() {
        return this;
    }

    @Override
    public void refresh() {
        BigDecimal threshold = appSettingService.getSavingsInterestWarningThreshold();
        savingsInterestThreshold.setValue(threshold);
        VisionConfig visionConfig = appSettingService.getVisionConfig();
        if (visionConfig != null) {
            visionPreset.setValue(visionConfig.preset() == null ? VisionPreset.DISABLED : visionConfig.preset());
            visionBaseUrl.setValue(visionConfig.baseUrl() == null ? "" : visionConfig.baseUrl());
            setVisionModelOptions(availableModels, visionConfig.model());
            visionModel.setInvalid(false);
            visionApiKey.setValue(visionConfig.apiKey() == null ? "" : visionConfig.apiKey());
            updateVisionFieldStates(visionPreset.getValue());
        }
    }

    private VerticalLayout createHeader() {
        Span title = new Span(getTranslation("admin.settings.title"));
        title.addClassName("admin-tab-title");

        Paragraph description = new Paragraph(getTranslation("admin.settings.description"));
        description.addClassNames(LumoUtility.TextColor.SECONDARY, LumoUtility.Margin.Top.NONE);

        VerticalLayout header = new VerticalLayout(title, description);
        header.setPadding(false);
        header.setSpacing(false);
        return header;
    }

    private FormLayout createForm() {
        savingsInterestThreshold.setLabel(getTranslation("admin.settings.field.savings-interest-threshold"));
        savingsInterestThreshold.setHelperText(getTranslation("admin.settings.hint.savings-interest-threshold"));
        savingsInterestThreshold.setSuffixComponent(new Span("%"));
        savingsInterestThreshold.setWidth("250px");

        H3 visionHeading = new H3(getTranslation("admin.settings.vision.title"));
        visionHeading.addClassNames(LumoUtility.FontSize.MEDIUM, LumoUtility.Margin.Top.MEDIUM,
                LumoUtility.Margin.Bottom.XSMALL);

        visionPreset.setLabel(getTranslation("admin.settings.vision.preset"));
        visionPreset.setItems(VisionPreset.values());
        visionPreset.setItemLabelGenerator(preset -> getTranslation("admin.settings.vision.preset." + preset.name()));
        visionPreset.setValue(VisionPreset.DISABLED);
        visionPreset.setWidth("280px");
        visionPreset.addValueChangeListener(event -> {
            VisionPreset selected = event.getValue() == null ? VisionPreset.DISABLED : event.getValue();
            if (event.isFromClient() && selected != VisionPreset.CUSTOM) {
                visionBaseUrl.setValue(selected.defaultBaseUrl());
                setVisionModelOptions(availableModels, selected.defaultModel());
                visionModel.setInvalid(false);
            }
            updateVisionFieldStates(selected);
        });

        visionBaseUrl.setLabel(getTranslation("admin.settings.vision.base-url"));
        visionBaseUrl.setWidth("420px");

        visionModel.setLabel(getTranslation("admin.settings.vision.model"));
        visionModel.setAllowCustomValue(true);
        visionModel.setItems(availableModels);
        visionModel.setWidth("320px");
        visionModel.addCustomValueSetListener(event -> {
            String custom = event.getDetail() == null ? "" : event.getDetail().trim();
            if (!custom.isBlank()) {
                setVisionModelOptions(availableModels, custom);
                visionModel.setInvalid(false);
            }
        });
        visionModel.addValueChangeListener(event -> {
            if (event.isFromClient()) {
                visionModel.setInvalid(false);
            }
        });

        fetchModelsButton.setText(getTranslation("admin.settings.vision.models.fetch"));
        fetchModelsButton.setIcon(VaadinIcon.REFRESH.create());
        fetchModelsButton.addClickListener(event -> fetchVisionModels());
        fetchModelsButton.setVisible(visionMeterClient != null);

        modelRow.add(visionModel, fetchModelsButton);
        modelRow.setDefaultVerticalComponentAlignment(Alignment.BASELINE);

        visionApiKey.setLabel(getTranslation("admin.settings.vision.api-key"));
        visionApiKey.setHelperText(getTranslation("admin.settings.vision.api-key.hint"));
        visionApiKey.setWidth("420px");

        testVisionButton.setText(getTranslation("admin.settings.vision.test"));
        testVisionButton.setIcon(VaadinIcon.PLUG.create());
        testVisionButton.addClickListener(event -> testVisionConnection());
        testVisionButton.setVisible(visionMeterClient != null);

        saveButton.setText(getTranslation("admin.settings.button.save"));
        saveButton.setIcon(VaadinIcon.CHECK.create());
        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveButton.addClickListener(event -> save());

        HorizontalLayout actions = new HorizontalLayout(saveButton, testVisionButton);
        actions.setDefaultVerticalComponentAlignment(Alignment.BASELINE);

        FormLayout form = new FormLayout(
                savingsInterestThreshold,
                visionHeading,
                visionPreset,
                visionBaseUrl,
                modelRow,
                visionApiKey,
                actions);
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1));
        updateVisionFieldStates(VisionPreset.DISABLED);
        return form;
    }

    private void updateVisionFieldStates(VisionPreset preset) {
        boolean enabled = preset != null && preset != VisionPreset.DISABLED;
        visionBaseUrl.setVisible(enabled);
        modelRow.setVisible(enabled);
        visionModel.setVisible(enabled);
        fetchModelsButton.setEnabled(enabled);
        visionApiKey.setVisible(enabled);
        testVisionButton.setEnabled(enabled);
        if (!enabled) {
            visionModel.setInvalid(false);
        }
    }

    void setVisionModelOptions(List<String> models, String preferredSelection) {
        availableModels = models == null ? List.of() : List.copyOf(models);
        Set<String> items = new LinkedHashSet<>(availableModels);
        String trimmedSelection = preferredSelection == null ? "" : preferredSelection.trim();
        if (!trimmedSelection.isBlank()) {
            items.add(trimmedSelection);
        }
        visionModel.setItems(items);
        if (!trimmedSelection.isBlank()) {
            visionModel.setValue(trimmedSelection);
        } else {
            visionModel.clear();
        }
    }

    void fetchVisionModels() {
        if (visionMeterClient == null) {
            return;
        }
        try {
            List<String> models = visionMeterClient.listModels(currentVisionConfig());
            if (models.isEmpty()) {
                Notification.show(getTranslation("admin.settings.vision.models.none"), 4000,
                        Notification.Position.MIDDLE);
                return;
            }
            String current = visionModel.getValue() == null ? "" : visionModel.getValue().trim();
            String matched = findMatchingModel(models, current);
            String nextSelection = matched != null ? matched : models.get(0);
            setVisionModelOptions(models, nextSelection);
            visionModel.setInvalid(false);
            Notification.show(getTranslation("admin.settings.vision.models.fetched", models.size()), 3000,
                    Notification.Position.MIDDLE);
        } catch (RuntimeException exception) {
            Notification.show(getTranslation("admin.settings.vision.models.error", exception.getMessage()), 5000,
                    Notification.Position.MIDDLE);
        }
    }

    void testVisionConnection() {
        if (visionMeterClient == null) {
            return;
        }
        VisionConfig config = currentVisionConfig();
        try {
            List<String> models = visionMeterClient.listModels(config);
            if (!models.isEmpty()) {
                String currentModel = config.normalizedModel();
                String matchedModel = findMatchingModel(models, currentModel);
                setVisionModelOptions(models, matchedModel != null ? matchedModel : currentModel);
                if (matchedModel == null) {
                    String warning = getTranslation("admin.settings.vision.test.model-unavailable",
                            models.size(), currentModel);
                    visionModel.setErrorMessage(warning);
                    visionModel.setInvalid(true);
                    Notification.show(warning, 5000, Notification.Position.MIDDLE);
                    return;
                }
                visionModel.setInvalid(false);
                Notification.show(getTranslation("admin.settings.vision.test.ok.count", models.size()), 3000,
                        Notification.Position.MIDDLE);
                return;
            }
            visionModel.setInvalid(false);
            Notification.show(getTranslation("admin.settings.vision.test.ok"), 3000,
                    Notification.Position.MIDDLE);
        } catch (RuntimeException exception) {
            Notification.show(getTranslation("admin.settings.vision.test.error", exception.getMessage()), 5000,
                    Notification.Position.MIDDLE);
        }
    }

    private static String findMatchingModel(List<String> models, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        return models.stream()
                .filter(entry -> entry.equalsIgnoreCase(candidate.trim()))
                .findFirst()
                .orElse(null);
    }

    private static List<String> defaultPresetModels() {
        Set<String> defaults = new LinkedHashSet<>();
        for (VisionPreset preset : VisionPreset.values()) {
            if (!preset.defaultModel().isBlank()) {
                defaults.add(preset.defaultModel());
            }
        }
        return List.copyOf(defaults);
    }

    private VisionConfig currentVisionConfig() {
        return new VisionConfig(
                visionPreset.getValue() == null ? VisionPreset.DISABLED : visionPreset.getValue(),
                visionBaseUrl.getValue(),
                visionModel.getValue(),
                visionApiKey.getValue());
    }

    void save() {
        BigDecimal value = savingsInterestThreshold.getValue();
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            savingsInterestThreshold.setErrorMessage(getTranslation("finance.editor.error.interest-negative"));
            savingsInterestThreshold.setInvalid(true);
            return;
        }
        savingsInterestThreshold.setInvalid(false);
        appSettingService.setSavingsInterestWarningThreshold(value);
        appSettingService.setVisionConfig(currentVisionConfig());
        Notification.show(getTranslation("admin.settings.notification.saved"), 3000,
                Notification.Position.MIDDLE);
    }
}
