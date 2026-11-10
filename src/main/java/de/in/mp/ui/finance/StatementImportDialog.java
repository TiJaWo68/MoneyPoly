package de.in.mp.ui.finance;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
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
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.data.renderer.ComponentRenderer;

import de.in.mp.finance.Account;
import de.in.mp.finance.AccountService;
import de.in.mp.finance.AccountType;
import de.in.mp.i18n.DateFormatter;
import de.in.mp.i18n.MoneyFormatter;
import de.in.mp.importer.StatementEntry;
import de.in.mp.importer.StatementImportService;
import de.in.mp.importer.StatementImportService.StatementAssignment;
import de.in.mp.importer.StatementMatch;

/**
 * Upload of one or more bank statement PDFs with a preview of all detected
 * accounts. Unresolved entries are assigned by the user; the assignment is
 * remembered for future imports of the same statement type.
 */
public class StatementImportDialog extends Dialog {

    private static final String PDF_MIME_TYPE = "application/pdf";
    private static final int MAX_FILES = 24;

    private final StatementImportService importService;
    private final AccountService accountService;

    private final Upload upload;
    private final Grid<PreviewRow> grid = new Grid<>();
    private final Button importButton = new Button();
    private final Map<String, ByteArrayOutputStream> buffers = new HashMap<>();
    private final List<PreviewRow> rows = new ArrayList<>();

    private List<Account> selectableAccounts = List.of();
    private Map<UUID, Account> accountsById = Map.of();
    private Runnable onImported;

    public StatementImportDialog(StatementImportService importService, AccountService accountService) {
        this.importService = importService;
        this.accountService = accountService;

        setHeaderTitle(getTranslation("finance.statement.title"));
        setWidth("1100px");

        upload = new Upload(this::receiveUpload);
        upload.setAcceptedFileTypes(".pdf", PDF_MIME_TYPE);
        upload.setMaxFiles(MAX_FILES);
        upload.setUploadButton(new Button(getTranslation("finance.statement.upload.button")));
        upload.setDropLabel(new Span(getTranslation("finance.statement.upload.drop")));
        upload.setWidthFull();
        upload.addSucceededListener(event -> analyze(event.getFileName()));

        configureGrid();

        importButton.setText(getTranslation("finance.statement.button.import"));
        importButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        importButton.addClickListener(event -> executeImport());
        Button cancelButton = new Button(getTranslation("finance.editor.button.cancel"), event -> close());

        HorizontalLayout actions = new HorizontalLayout(cancelButton, importButton);
        actions.setWidthFull();
        actions.setJustifyContentMode(JustifyContentMode.END);

        VerticalLayout layout = new VerticalLayout(
                new Paragraph(getTranslation("finance.statement.description")), upload, grid, actions);
        layout.setPadding(false);
        add(layout);
        setCloseOnEsc(true);
        setCloseOnOutsideClick(false);
    }

    public void openForImport(Runnable onImported) {
        this.onImported = onImported;
        List<Account> all = accountService.findAll();
        accountsById = AccountLabels.byId(all);
        selectableAccounts = AccountLabels.matrixOrder(all, accountsById).stream()
                .filter(account -> account.getAccountType() != AccountType.INSTITUTION)
                .toList();
        buffers.clear();
        rows.clear();
        grid.setItems(rows);
        upload.clearFileList();
        updateImportButton();
        open();
    }

    private ByteArrayOutputStream receiveUpload(String fileName, String mimeType) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        buffers.put(fileName, buffer);
        return buffer;
    }

    private void analyze(String fileName) {
        ByteArrayOutputStream buffer = buffers.remove(fileName);
        if (buffer == null) {
            return;
        }
        rows.removeIf(row -> row.match.fileName().equals(fileName));
        try {
            for (StatementMatch match : importService.analyze(fileName, buffer.toByteArray())) {
                rows.add(new PreviewRow(match));
            }
        } catch (IllegalArgumentException exception) {
            Notification notification = Notification.show(
                    getTranslation("finance.statement.error.unsupported", fileName), 6000,
                    Notification.Position.MIDDLE);
            notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
        grid.setItems(rows);
        updateImportButton();
    }

    private void configureGrid() {
        grid.addColumn(row -> row.match.fileName())
                .setHeader(getTranslation("finance.statement.column.file"))
                .setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(row -> row.entry().bank() + " – " + row.entry().accountLabel())
                .setHeader(getTranslation("finance.statement.column.account"))
                .setAutoWidth(true);
        grid.addColumn(row -> identifier(row.entry()))
                .setHeader(getTranslation("finance.grid.column.account-number"))
                .setAutoWidth(true);
        grid.addColumn(row -> DateFormatter.format(row.entry().closingDate(), locale()))
                .setHeader(getTranslation("finance.balance.field.date"))
                .setAutoWidth(true);
        grid.addColumn(row -> formatMoney(row.entry().closingBalance()))
                .setHeader(getTranslation("finance.grid.column.balance"))
                .setTextAlign(ColumnTextAlign.END).setAutoWidth(true);
        grid.addColumn(row -> formatMoney(row.entry().inflow()))
                .setHeader(getTranslation("finance.balance.column.inflow"))
                .setTextAlign(ColumnTextAlign.END).setAutoWidth(true);
        grid.addColumn(row -> formatMoney(row.entry().outflow()))
                .setHeader(getTranslation("finance.balance.column.outflow"))
                .setTextAlign(ColumnTextAlign.END).setAutoWidth(true);
        grid.addColumn(new ComponentRenderer<>(this::renderTarget))
                .setHeader(getTranslation("finance.statement.column.target"))
                .setWidth("260px").setFlexGrow(1);
        grid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_COMPACT);
        grid.setAllRowsVisible(true);
    }

    private ComboBox<Account> renderTarget(PreviewRow row) {
        ComboBox<Account> target = new ComboBox<>();
        target.setItems(selectableAccounts);
        target.setItemLabelGenerator(account -> AccountLabels.qualifiedName(account, accountsById));
        target.setPlaceholder(getTranslation("finance.statement.placeholder.choose"));
        target.setClearButtonVisible(true);
        target.setWidthFull();
        if (row.accountId != null) {
            target.setValue(accountsById.get(row.accountId));
        }
        target.setHelperText(getTranslation("finance.statement.match." + row.match.source().name()));
        target.addValueChangeListener(event -> {
            row.accountId = event.getValue() == null ? null : event.getValue().getId();
            updateImportButton();
        });
        return target;
    }

    private void updateImportButton() {
        importButton.setEnabled(rows.stream().anyMatch(row -> row.accountId != null));
    }

    private void executeImport() {
        List<StatementAssignment> assignments = rows.stream()
                .filter(row -> row.accountId != null)
                .map(row -> new StatementAssignment(row.match.fileName(), row.entry(), row.accountId))
                .toList();
        try {
            int recorded = importService.apply(assignments);
            long skipped = rows.size() - assignments.size();
            Notification notification = Notification.show(
                    getTranslation("finance.statement.notification.imported", recorded, skipped), 5000,
                    Notification.Position.BOTTOM_START);
            notification.addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            close();
            if (onImported != null) {
                onImported.run();
            }
        } catch (IllegalArgumentException exception) {
            Notification.show(getTranslation("finance.notification.error", exception.getMessage()), 6000,
                    Notification.Position.MIDDLE);
        }
    }

    private static String identifier(StatementEntry entry) {
        if (entry.iban() != null) {
            return entry.iban();
        }
        if (entry.accountNumber() != null) {
            return entry.accountNumber();
        }
        return entry.accountHolder() == null ? "" : entry.accountHolder();
    }

    private static String formatMoney(BigDecimal amount) {
        return amount == null ? "" : MoneyFormatter.format(amount, locale());
    }

    private static Locale locale() {
        return UI.getCurrent() != null ? UI.getCurrent().getLocale() : Locale.GERMANY;
    }

    /**
     * Preview line with the user-editable target account.
     */
    private static final class PreviewRow {
        private final StatementMatch match;
        private UUID accountId;

        private PreviewRow(StatementMatch match) {
            this.match = match;
            this.accountId = match.resolved() ? match.account().getId() : null;
        }

        private StatementEntry entry() {
            return match.entry();
        }
    }
}
