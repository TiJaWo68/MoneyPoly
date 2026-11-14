package de.in.mp.ui.admin;

import java.math.BigDecimal;
import java.util.List;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.renderer.ComponentRenderer;

import de.in.mp.finance.Household;
import de.in.mp.finance.HouseholdService;
import de.in.mp.ui.GridActionButton;

/**
 * Admin dialog to create, rename and toggle {@link Household} tenants.
 */
public class HouseholdDialog extends Dialog {

    private static final String DIALOG_WIDTH = "680px";
    private static final String GRID_HEIGHT = "240px";

    private final HouseholdService householdService;
    private final Grid<Household> grid = new Grid<>(Household.class, false);
    private final TextField nameField = new TextField();
    private final BigDecimalField warningThresholdField = new BigDecimalField();
    private final Checkbox activeCheckbox = new Checkbox();
    private final Button saveButton = new Button();

    private Household editingHousehold;
    private Runnable onChanged;

    public HouseholdDialog(HouseholdService householdService) {
        this.householdService = householdService;
        setWidth(DIALOG_WIDTH);
        setCloseOnEsc(true);
        setCloseOnOutsideClick(false);
        setHeaderTitle(getTranslation("admin.household.dialog.title"));

        configureGrid();
        configureForm();

        HorizontalLayout formRow = new HorizontalLayout(nameField, warningThresholdField, activeCheckbox, saveButton);
        formRow.setWidthFull();
        formRow.setDefaultVerticalComponentAlignment(Alignment.END);
        formRow.expand(nameField);

        Button closeButton = new Button(getTranslation("ui.dialog.close"), e -> close());
        VerticalLayout content = new VerticalLayout(grid, formRow);
        content.setPadding(false);
        content.setWidthFull();
        add(content);
        getFooter().add(closeButton);
    }

    public void openDialog(Runnable onChanged) {
        this.onChanged = onChanged;
        resetForm();
        refreshGrid();
        open();
    }

    private void configureGrid() {
        grid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_COMPACT);
        grid.setHeight(GRID_HEIGHT);
        grid.setWidthFull();

        grid.addColumn(Household::getName)
                .setHeader(getTranslation("admin.household.field.name"))
                .setFlexGrow(1);
        grid.addColumn(h -> h.isDemo() ? getTranslation("admin.household.type.demo")
                : getTranslation("admin.household.type.standard"))
                .setHeader(getTranslation("admin.household.field.type"))
                .setAutoWidth(true);
        grid.addColumn(h -> h.getDashboardWarningThreshold() != null
                ? h.getDashboardWarningThreshold() + " %"
                : "–")
                .setHeader(getTranslation("admin.household.field.threshold"))
                .setAutoWidth(true);
        grid.addColumn(h -> h.isActive() ? "✓" : "–")
                .setHeader(getTranslation("admin.household.field.active"))
                .setAutoWidth(true);
        grid.addColumn(new ComponentRenderer<>(this::renderActions))
                .setHeader("")
                .setAutoWidth(true);
    }

    private HorizontalLayout renderActions(Household household) {
        Button editButton = GridActionButton.create(
                VaadinIcon.EDIT,
                getTranslation("admin.household.action.edit"),
                e -> populateForm(household));
        return new HorizontalLayout(editButton);
    }

    private void configureForm() {
        nameField.setLabel(getTranslation("admin.household.field.name"));
        warningThresholdField.setLabel(getTranslation("admin.household.field.threshold"));
        warningThresholdField.setWidth("140px");
        activeCheckbox.setLabel(getTranslation("admin.household.field.active"));
        saveButton.setText(getTranslation("admin.household.button.save"));
        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveButton.addClickListener(e -> saveForm());
    }

    private void populateForm(Household household) {
        editingHousehold = household;
        nameField.setValue(household.getName() != null ? household.getName() : "");
        warningThresholdField.setValue(household.getDashboardWarningThreshold() != null
                ? household.getDashboardWarningThreshold()
                : Household.DEFAULT_WARNING_THRESHOLD);
        activeCheckbox.setValue(household.isActive());
    }

    private void resetForm() {
        editingHousehold = null;
        nameField.clear();
        warningThresholdField.setValue(Household.DEFAULT_WARNING_THRESHOLD);
        activeCheckbox.setValue(true);
    }

    private void saveForm() {
        String name = nameField.getValue() == null ? "" : nameField.getValue().trim();
        if (name.isEmpty()) {
            Notification.show(getTranslation("admin.household.error.name-required"), 3000,
                    Notification.Position.MIDDLE);
            return;
        }
        Household target = editingHousehold != null ? editingHousehold : new Household();
        target.setName(name);
        BigDecimal threshold = warningThresholdField.getValue();
        target.setDashboardWarningThreshold(threshold != null ? threshold : Household.DEFAULT_WARNING_THRESHOLD);
        target.setActive(Boolean.TRUE.equals(activeCheckbox.getValue()));

        try {
            householdService.save(target);
            resetForm();
            refreshGrid();
            if (onChanged != null) {
                onChanged.run();
            }
        } catch (IllegalArgumentException ex) {
            Notification notification = Notification.show(ex.getMessage(), 4000, Notification.Position.MIDDLE);
            notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }

    private void refreshGrid() {
        List<Household> all = householdService.findAll();
        grid.setItems(all);
    }
}
