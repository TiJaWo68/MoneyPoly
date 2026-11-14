package de.in.mp.ui.energy;

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
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.renderer.ComponentRenderer;

import de.in.mp.energy.EnergyHouse;
import de.in.mp.energy.EnergyHouseService;
import de.in.mp.ui.GridActionButton;

/**
 * Admin dialog to create, edit, and delete {@link EnergyHouse} entries.
 */
public class EnergyHouseDialog extends Dialog {

    private static final int DEFAULT_DISPLAY_ORDER = 100;
    private static final String DIALOG_WIDTH = "620px";
    private static final String GRID_HEIGHT = "240px";

    private final EnergyHouseService houseService;
    private final Grid<EnergyHouse> grid = new Grid<>(EnergyHouse.class, false);
    private final TextField nameField = new TextField();
    private final IntegerField orderField = new IntegerField();
    private final Checkbox activeCheckbox = new Checkbox();
    private final Button saveButton = new Button();

    private EnergyHouse editingHouse;
    private Runnable onChanged;

    public EnergyHouseDialog(EnergyHouseService houseService) {
        this.houseService = houseService;
        setWidth(DIALOG_WIDTH);
        setCloseOnEsc(true);
        setCloseOnOutsideClick(false);
        setHeaderTitle(getTranslation("energy.house.dialog.title"));

        configureGrid();
        configureForm();

        HorizontalLayout formRow = new HorizontalLayout(nameField, orderField, activeCheckbox, saveButton);
        formRow.setWidthFull();
        formRow.setDefaultVerticalComponentAlignment(Alignment.END);
        formRow.expand(nameField);

        Button closeButton = new Button(getTranslation("energy.button.cancel"), e -> close());
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

        grid.addColumn(EnergyHouse::getName)
                .setHeader(getTranslation("energy.house.field.name"))
                .setFlexGrow(1);
        grid.addColumn(EnergyHouse::getDisplayOrder)
                .setHeader(getTranslation("energy.house.field.order"))
                .setAutoWidth(true);
        grid.addColumn(house -> house.isActive() ? "✓" : "–")
                .setHeader(getTranslation("energy.house.field.active"))
                .setAutoWidth(true);
        grid.addColumn(new ComponentRenderer<>(this::renderActions))
                .setHeader(getTranslation("energy.grid.column.actions"))
                .setAutoWidth(true);
    }

    private HorizontalLayout renderActions(EnergyHouse house) {
        Button editButton = GridActionButton.create(
                VaadinIcon.EDIT,
                getTranslation("energy.button.save"),
                e -> editHouse(house));
        Button deleteButton = GridActionButton.createDanger(
                VaadinIcon.TRASH,
                getTranslation("energy.readings.action.delete-row"),
                e -> deleteHouse(house));
        return new HorizontalLayout(editButton, deleteButton);
    }

    private void configureForm() {
        nameField.setLabel(getTranslation("energy.house.field.name"));
        nameField.setRequiredIndicatorVisible(true);

        orderField.setLabel(getTranslation("energy.house.field.order"));
        orderField.setWidth("100px");
        orderField.setStepButtonsVisible(true);

        activeCheckbox.setLabel(getTranslation("energy.house.field.active"));

        saveButton.setText(getTranslation("energy.house.button.add"));
        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveButton.addClickListener(e -> saveHouse());
    }

    private void editHouse(EnergyHouse house) {
        this.editingHouse = house;
        nameField.setValue(house.getName() != null ? house.getName() : "");
        orderField.setValue(house.getDisplayOrder());
        activeCheckbox.setValue(house.isActive());
        saveButton.setText(getTranslation("energy.button.save"));
    }

    private void resetForm() {
        this.editingHouse = null;
        nameField.clear();
        orderField.setValue(DEFAULT_DISPLAY_ORDER);
        activeCheckbox.setValue(true);
        saveButton.setText(getTranslation("energy.house.button.add"));
    }

    private void saveHouse() {
        EnergyHouse target = editingHouse != null ? editingHouse : new EnergyHouse();
        target.setName(nameField.getValue());
        target.setDisplayOrder(orderField.getValue() != null ? orderField.getValue() : DEFAULT_DISPLAY_ORDER);
        target.setActive(Boolean.TRUE.equals(activeCheckbox.getValue()));

        try {
            houseService.save(target);
            Notification.show(getTranslation("energy.house.notification.saved"),
                            3000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            resetForm();
            refreshGrid();
            if (onChanged != null) {
                onChanged.run();
            }
        } catch (RuntimeException ex) {
            Notification.show(ex.getMessage(), 4000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }

    private void deleteHouse(EnergyHouse house) {
        try {
            houseService.delete(house.getId());
            Notification.show(getTranslation("energy.house.notification.deleted"),
                            3000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            if (editingHouse != null && house.getId().equals(editingHouse.getId())) {
                resetForm();
            }
            refreshGrid();
            if (onChanged != null) {
                onChanged.run();
            }
        } catch (RuntimeException ex) {
            Notification.show(ex.getMessage(), 4000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }

    private void refreshGrid() {
        List<EnergyHouse> houses = houseService.findAll();
        grid.setItems(houses);
    }
}
