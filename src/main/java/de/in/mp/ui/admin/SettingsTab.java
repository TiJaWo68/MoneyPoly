package de.in.mp.ui.admin;

import java.math.BigDecimal;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.theme.lumo.LumoUtility;

import de.in.mp.admin.AppSettingService;

/**
 * Administration tab for configuring application-wide settings and thresholds.
 */
public class SettingsTab extends VerticalLayout implements AdminTab {

    private final AppSettingService appSettingService;

    final BigDecimalField savingsInterestThreshold = new BigDecimalField();
    final Button saveButton = new Button();

    public SettingsTab(AppSettingService appSettingService) {
        this.appSettingService = appSettingService;

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

        saveButton.setText(getTranslation("admin.settings.button.save"));
        saveButton.setIcon(VaadinIcon.CHECK.create());
        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveButton.addClickListener(event -> save());

        HorizontalLayout actions = new HorizontalLayout(saveButton);
        actions.setDefaultVerticalComponentAlignment(Alignment.BASELINE);

        FormLayout form = new FormLayout(savingsInterestThreshold, actions);
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1));
        return form;
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
        Notification.show(getTranslation("admin.settings.notification.saved"), 3000,
                Notification.Position.MIDDLE);
    }
}
