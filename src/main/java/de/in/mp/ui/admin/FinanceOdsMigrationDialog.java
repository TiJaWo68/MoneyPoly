package de.in.mp.ui.admin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Locale;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.data.renderer.ComponentRenderer;

import de.in.mp.finance.AccountType;
import de.in.mp.i18n.DateFormatter;
import de.in.mp.i18n.MoneyFormatter;
import de.in.mp.importer.FinanceOdsImportService;
import de.in.mp.importer.FinanceOdsImportService.OdsMigrationResult;
import de.in.mp.importer.OdsAccountColumn;

/**
 * One-time migration of the legacy finance spreadsheet.
 * <p>
 * After upload, every account column is listed with editable settings so
 * that missing master data (institution, name, type, IBAN, bank, holder,
 * active flag) can be completed before the import. Columns can be excluded or
 * merged into another column (e.g. a detailed monthly overview into the
 * matching account of the balance matrix).
 */
public class FinanceOdsMigrationDialog extends Dialog {

    private static final String ODS_MIME_TYPE = "application/vnd.oasis.opendocument.spreadsheet";

    private final FinanceOdsImportService importService;

    private final Upload upload;
    private final Grid<OdsAccountColumn> grid = new Grid<>();
    private final Button selectAllButton = new Button();
    private final Button deselectAllButton = new Button();
    private final Button runButton = new Button();

    private ByteArrayOutputStream buffer;
    private List<OdsAccountColumn> columns = List.of();

    public FinanceOdsMigrationDialog(FinanceOdsImportService importService) {
        this.importService = importService;

        setHeaderTitle(getTranslation("admin.migration.finance.title"));
        setWidth("95vw");
        setHeight("90vh");

        upload = new Upload(this::receiveUpload);
        upload.setAcceptedFileTypes(".ods", ODS_MIME_TYPE);
        upload.setMaxFiles(1);
        upload.setUploadButton(new Button(getTranslation("admin.migration.upload.button")));
        upload.setDropLabel(new Span(getTranslation("admin.migration.upload.drop")));
        upload.setWidthFull();
        upload.addSucceededListener(event -> analyze());

        configureGrid();

        selectAllButton.setText(getTranslation("admin.migration.button.select-all"));
        selectAllButton.addClickListener(event -> setAllIncluded(true));

        deselectAllButton.setText(getTranslation("admin.migration.button.deselect-all"));
        deselectAllButton.addClickListener(event -> setAllIncluded(false));

        runButton.setText(getTranslation("admin.migration.button.run"));
        runButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        runButton.addClickListener(event -> execute());
        Button cancelButton = new Button(getTranslation("admin.button.cancel"), event -> close());

        HorizontalLayout selectionActions = new HorizontalLayout(selectAllButton, deselectAllButton);
        HorizontalLayout dialogActions = new HorizontalLayout(cancelButton, runButton);
        HorizontalLayout actions = new HorizontalLayout(selectionActions, dialogActions);
        actions.setWidthFull();
        actions.setJustifyContentMode(JustifyContentMode.BETWEEN);

        VerticalLayout layout = new VerticalLayout(
                new Paragraph(getTranslation("admin.migration.finance.description")), upload, grid, actions);
        layout.setPadding(false);
        layout.setSizeFull();
        layout.setFlexGrow(1, grid);
        add(layout);
        setCloseOnEsc(true);
        setCloseOnOutsideClick(false);
    }

    public void openForMigration() {
        buffer = null;
        setColumnsForTest(List.of());
        upload.clearFileList();
        open();
    }

    void setColumnsForTest(List<OdsAccountColumn> newColumns) {
        this.columns = newColumns;
        grid.setItems(columns);
        boolean hasColumns = !columns.isEmpty();
        selectAllButton.setEnabled(hasColumns);
        deselectAllButton.setEnabled(hasColumns);
        runButton.setEnabled(hasColumns);
    }

    void setAllIncluded(boolean include) {
        columns.forEach(column -> column.setInclude(include));
        grid.getDataProvider().refreshAll();
    }

    private ByteArrayOutputStream receiveUpload(String fileName, String mimeType) {
        buffer = new ByteArrayOutputStream();
        return buffer;
    }

    private void analyze() {
        try {
            setColumnsForTest(importService.analyze(new ByteArrayInputStream(buffer.toByteArray())));
        } catch (IllegalArgumentException exception) {
            Notification notification = Notification.show(
                    getTranslation("admin.notification.error", exception.getMessage()), 6000,
                    Notification.Position.MIDDLE);
            notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }

    private void configureGrid() {
        grid.addColumn(new ComponentRenderer<>(this::includeBox))
                .setHeader(getTranslation("admin.migration.column.include"))
                .setAutoWidth(true).setFlexGrow(0).setFrozen(true);
        grid.addColumn(OdsAccountColumn::sourceLabel)
                .setHeader(getTranslation("admin.migration.column.source"))
                .setTooltipGenerator(OdsAccountColumn::sheetName)
                .setAutoWidth(true).setFrozen(true);
        grid.addColumn(this::period)
                .setHeader(getTranslation("admin.migration.column.period"))
                .setAutoWidth(true);
        grid.addColumn(column -> column.latestBalance() == null ? ""
                : MoneyFormatter.format(column.latestBalance(), locale()))
                .setHeader(getTranslation("admin.migration.column.latest"))
                .setTextAlign(ColumnTextAlign.END).setAutoWidth(true);
        grid.addColumn(new ComponentRenderer<>(column -> textField(column.getInstitutionName(),
                column::setInstitutionName)))
                .setHeader(getTranslation("admin.migration.column.institution")).setWidth("160px");
        grid.addColumn(new ComponentRenderer<>(column -> textField(column.getAccountName(),
                column::setAccountName)))
                .setHeader(getTranslation("finance.editor.field.name")).setWidth("160px");
        grid.addColumn(new ComponentRenderer<>(this::typeBox))
                .setHeader(getTranslation("finance.editor.field.type")).setWidth("190px");
        grid.addColumn(new ComponentRenderer<>(column -> textField(column.getAccountNumber(),
                column::setAccountNumber)))
                .setHeader(getTranslation("finance.editor.field.account-number")).setWidth("220px");
        grid.addColumn(new ComponentRenderer<>(column -> textField(column.getBankName(),
                column::setBankName)))
                .setHeader(getTranslation("finance.editor.field.bank")).setWidth("150px");
        grid.addColumn(new ComponentRenderer<>(column -> textField(column.getAccountHolder(),
                column::setAccountHolder)))
                .setHeader(getTranslation("finance.editor.field.holder")).setWidth("150px");
        grid.addColumn(new ComponentRenderer<>(this::activeBox))
                .setHeader(getTranslation("finance.grid.column.status"))
                .setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(new ComponentRenderer<>(this::mergeBox))
                .setHeader(getTranslation("admin.migration.column.merge")).setWidth("200px");
        grid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES);
    }

    private String period(OdsAccountColumn column) {
        return getTranslation("admin.migration.period",
                DateFormatter.format(column.earliestDate(), locale()),
                DateFormatter.format(column.latestDate(), locale()),
                column.values().size());
    }

    private Checkbox includeBox(OdsAccountColumn column) {
        Checkbox box = new Checkbox(getTranslation("admin.migration.column.include"), column.isInclude());
        box.addValueChangeListener(event -> column.setInclude(event.getValue()));
        return box;
    }

    private Checkbox activeBox(OdsAccountColumn column) {
        Checkbox box = new Checkbox(getTranslation("finance.account.status.active"), column.isActive());
        box.addValueChangeListener(event -> column.setActive(event.getValue()));
        return box;
    }

    private ComboBox<AccountType> typeBox(OdsAccountColumn column) {
        ComboBox<AccountType> box = new ComboBox<>();
        box.setItems(List.of(AccountType.CHECKING, AccountType.SAVINGS, AccountType.INVESTMENT, AccountType.LOAN));
        box.setItemLabelGenerator(type -> getTranslation("finance.account.type." + type.name()));
        box.setValue(column.getAccountType());
        box.setWidthFull();
        box.addValueChangeListener(event -> column.setAccountType(event.getValue()));
        return box;
    }

    private ComboBox<OdsAccountColumn> mergeBox(OdsAccountColumn column) {
        ComboBox<OdsAccountColumn> box = new ComboBox<>();
        box.setItems(columns.stream().filter(other -> other != column).toList());
        box.setItemLabelGenerator(OdsAccountColumn::sourceLabel);
        box.setClearButtonVisible(true);
        box.setValue(column.getMergeInto());
        box.setWidthFull();
        box.addValueChangeListener(event -> column.setMergeInto(event.getValue()));
        return box;
    }

    private static TextField textField(String value, java.util.function.Consumer<String> setter) {
        TextField field = new TextField();
        field.setValue(value == null ? "" : value);
        field.setWidthFull();
        field.addValueChangeListener(event -> setter.accept(event.getValue()));
        return field;
    }

    private void execute() {
        try {
            OdsMigrationResult result = importService.execute(columns);
            Notification notification = Notification.show(getTranslation("admin.migration.finance.success",
                    result.accountsCreated(), result.institutions(), result.snapshotsRecorded()), 6000,
                    Notification.Position.BOTTOM_START);
            notification.addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            close();
        } catch (IllegalArgumentException exception) {
            Notification.show(getTranslation("admin.notification.error", exception.getMessage()), 6000,
                    Notification.Position.MIDDLE);
        }
    }

    private static Locale locale() {
        return UI.getCurrent() != null ? UI.getCurrent().getLocale() : Locale.GERMANY;
    }
}
