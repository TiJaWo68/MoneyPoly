package de.in.mp.ui.admin;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

import de.in.mp.admin.DatabaseResetService;
import de.in.mp.energy.EnergyOdsImportService;
import de.in.mp.importer.FinanceOdsImportService;
import de.in.mp.ui.energy.EnergyOdsImportDialog;

/**
 * One-time data migrations used when MoneyPoly is put into operation
 * (import of legacy spreadsheets) as well as database reset for repeatable
 * testing. Kept in the administration area so that the everyday views stay
 * focused on regular data entry.
 */
public class DataMigrationTab extends VerticalLayout implements AdminTab {

    private static final int NOTIFICATION_DURATION_MS = 5000;
    private static final String CONFIRM_DIALOG_WIDTH = "480px";

    private final DatabaseResetService databaseResetService;

    public DataMigrationTab(FinanceOdsImportService financeImportService,
            EnergyOdsImportService energyImportService,
            DatabaseResetService databaseResetService) {
        this.databaseResetService = databaseResetService;

        FinanceOdsMigrationDialog financeDialog = new FinanceOdsMigrationDialog(financeImportService);
        EnergyOdsImportDialog energyDialog = new EnergyOdsImportDialog(energyImportService);

        setSizeFull();
        setPadding(false);

        Span title = new Span(getTranslation("admin.migration.title"));
        title.addClassName("admin-tab-title");

        Button resetButton = new Button(
                getTranslation("admin.migration.reset.button"),
                VaadinIcon.TRASH.create(),
                event -> openResetConfirmationDialog());
        resetButton.addThemeVariants(ButtonVariant.LUMO_ERROR);

        add(title,
                new Paragraph(getTranslation("admin.migration.description")),
                section(getTranslation("admin.migration.finance.title"),
                        getTranslation("admin.migration.finance.summary"),
                        new Button(getTranslation("admin.migration.finance.button"), VaadinIcon.UPLOAD.create(),
                                event -> financeDialog.openForMigration())),
                section(getTranslation("energy.import.title"),
                        getTranslation("admin.migration.energy.summary"),
                        new Button(getTranslation("energy.button.import-ods"), VaadinIcon.UPLOAD.create(),
                                event -> energyDialog.openForImport(null))),
                section(getTranslation("admin.migration.reset.title"),
                        getTranslation("admin.migration.reset.summary"),
                        resetButton));
    }

    private void openResetConfirmationDialog() {
        Dialog confirmDialog = new Dialog();
        confirmDialog.setHeaderTitle(getTranslation("admin.migration.reset.confirm-title"));
        confirmDialog.setWidth(CONFIRM_DIALOG_WIDTH);
        confirmDialog.setCloseOnEsc(true);
        confirmDialog.setCloseOnOutsideClick(false);

        Paragraph warningText = new Paragraph(getTranslation("admin.migration.reset.confirm-message"));

        Button cancelButton = new Button(getTranslation("admin.button.cancel"), event -> confirmDialog.close());
        Button confirmButton = new Button(
                getTranslation("admin.migration.reset.confirm-button"),
                VaadinIcon.TRASH.create(),
                event -> {
                    executeDatabaseReset();
                    confirmDialog.close();
                });
        confirmButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR);

        HorizontalLayout actions = new HorizontalLayout(cancelButton, confirmButton);
        actions.setWidthFull();
        actions.setJustifyContentMode(JustifyContentMode.END);

        VerticalLayout dialogLayout = new VerticalLayout(warningText, actions);
        dialogLayout.setPadding(false);
        confirmDialog.add(dialogLayout);
        confirmDialog.open();
    }

    void executeDatabaseReset() {
        databaseResetService.resetAllDomainData();
        if (com.vaadin.flow.component.UI.getCurrent() != null) {
            Notification notification = Notification.show(
                    getTranslation("admin.migration.reset.success"),
                    NOTIFICATION_DURATION_MS,
                    Notification.Position.BOTTOM_START);
            notification.addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        }
    }

    private static VerticalLayout section(String heading, String summary, Button action) {
        action.setTooltipText(heading);
        VerticalLayout section = new VerticalLayout(new H4(heading), new Paragraph(summary), action);
        section.setPadding(false);
        section.setSpacing(false);
        return section;
    }

    @Override
    public String getLabelKey() {
        return "admin.tab.migration";
    }

    @Override
    public Component asComponent() {
        return this;
    }

    @Override
    public void refresh() {
        // Static content; the dialogs load their data on upload.
    }
}
