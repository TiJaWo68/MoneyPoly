package de.in.mp.ui.admin;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

import de.in.mp.energy.EnergyOdsImportService;
import de.in.mp.importer.FinanceOdsImportService;
import de.in.mp.ui.energy.EnergyOdsImportDialog;

/**
 * One-time data migrations used when MoneyPoly is put into operation
 * (import of legacy spreadsheets). Kept in the administration area so that
 * the everyday views stay focused on regular data entry.
 */
public class DataMigrationTab extends VerticalLayout implements AdminTab {

    public DataMigrationTab(FinanceOdsImportService financeImportService,
            EnergyOdsImportService energyImportService) {
        FinanceOdsMigrationDialog financeDialog = new FinanceOdsMigrationDialog(financeImportService);
        EnergyOdsImportDialog energyDialog = new EnergyOdsImportDialog(energyImportService);

        setSizeFull();
        setPadding(false);

        Span title = new Span(getTranslation("admin.migration.title"));
        title.addClassName("admin-tab-title");

        add(title,
                new Paragraph(getTranslation("admin.migration.description")),
                section(getTranslation("admin.migration.finance.title"),
                        getTranslation("admin.migration.finance.summary"),
                        new Button(getTranslation("admin.migration.finance.button"), VaadinIcon.UPLOAD.create(),
                                event -> financeDialog.openForMigration())),
                section(getTranslation("energy.import.title"),
                        getTranslation("admin.migration.energy.summary"),
                        new Button(getTranslation("energy.button.import-ods"), VaadinIcon.UPLOAD.create(),
                                event -> energyDialog.openForImport(null))));
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
