package de.in.mp.ui.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.BigDecimalField;

import de.in.mp.finance.Account;
import de.in.mp.finance.AccountService;
import de.in.mp.finance.AccountSnapshot;
import de.in.mp.finance.AccountSnapshotService;
import de.in.mp.finance.AccountType;
import de.in.mp.finance.BalanceDateRow;
import de.in.mp.i18n.DateFormatter;
import de.in.mp.i18n.MoneyFormatter;

/**
 * Quick entry of the balances of all active accounts for one reference date
 * (one row of the balance matrix). Each field shows the previous known balance
 * of the account as a hint; empty fields are left untouched.
 */
public class BalanceEntryDialog extends Dialog {

    private final AccountService accountService;
    private final AccountSnapshotService snapshotService;

    private final DatePicker date = new DatePicker();
    private final FormLayout accountFields = new FormLayout();
    private final Map<UUID, BigDecimalField> fieldsByAccount = new LinkedHashMap<>();
    private final Map<UUID, BigDecimalField> investedFieldsByAccount = new LinkedHashMap<>();

    private List<BalanceDateRow> history = List.of();
    private Runnable onSaved;

    public BalanceEntryDialog(AccountService accountService, AccountSnapshotService snapshotService) {
        this.accountService = accountService;
        this.snapshotService = snapshotService;

        date.setLabel(getTranslation("finance.balance.field.date"));
        date.addValueChangeListener(event -> updatePreviousHints());

        accountFields.setResponsiveSteps(
                new FormLayout.ResponsiveStep("0", 1),
                new FormLayout.ResponsiveStep("500px", 2));

        Button saveButton = new Button(getTranslation("finance.editor.button.save"), event -> save());
        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button cancelButton = new Button(getTranslation("finance.editor.button.cancel"), event -> close());

        HorizontalLayout actions = new HorizontalLayout(cancelButton, saveButton);
        actions.setWidthFull();
        actions.setJustifyContentMode(JustifyContentMode.END);

        setWidth("760px");
        add(date, accountFields, actions);
        setCloseOnEsc(true);
        setCloseOnOutsideClick(false);
    }

    public void openForCreate(Runnable onSaved) {
        open(getTranslation("finance.balance.title.new"), LocalDate.now(), Map.of(), onSaved);
    }

    public void openForEdit(BalanceDateRow row, Runnable onSaved) {
        open(getTranslation("finance.balance.title.edit"), row.date(), row.snapshotsByAccount(), onSaved);
    }

    private void open(String title, LocalDate value, Map<UUID, AccountSnapshot> existing, Runnable onSaved) {
        this.onSaved = onSaved;
        this.history = snapshotService.buildDateRows();
        setHeaderTitle(title);
        rebuildFields(existing);
        date.setValue(value);
        updatePreviousHints();
        open();
    }

    private void rebuildFields(Map<UUID, AccountSnapshot> existing) {
        accountFields.removeAll();
        fieldsByAccount.clear();
        investedFieldsByAccount.clear();

        List<Account> all = accountService.findAll();
        Map<UUID, Account> byId = AccountLabels.byId(all);
        for (Account account : AccountLabels.matrixOrder(all, byId)) {
            AccountSnapshot snapshot = existing.get(account.getId());
            if (!account.isActive() && snapshot == null) {
                continue;
            }
            String label = AccountLabels.qualifiedName(
                    account, byId, type -> getTranslation("finance.account.type." + type.name()));
            BigDecimalField field = new BigDecimalField(label);
            field.setWidthFull();
            if (snapshot != null) {
                field.setValue(snapshot.getBalance());
            }
            fieldsByAccount.put(account.getId(), field);
            accountFields.add(field);

            if (account.getAccountType() == AccountType.INVESTMENT) {
                BigDecimalField investedField = new BigDecimalField(
                        getTranslation("finance.balance.field.invested-capital", label));
                investedField.setWidthFull();
                if (snapshot != null) {
                    investedField.setValue(snapshot.getInvestedCapital());
                }
                investedFieldsByAccount.put(account.getId(), investedField);
                accountFields.add(investedField);
            }
        }
    }

    private void updatePreviousHints() {
        LocalDate reference = date.getValue();
        Locale locale = locale();
        for (Map.Entry<UUID, BigDecimalField> entry : fieldsByAccount.entrySet()) {
            AccountSnapshot previous = reference == null ? null : previousSnapshot(entry.getKey(), reference);
            entry.getValue().setHelperText(previous == null
                    ? getTranslation("finance.balance.hint.none")
                    : getTranslation("finance.balance.hint.previous",
                            MoneyFormatter.format(previous.getBalance(), locale),
                            DateFormatter.format(previous.getSnapshotDate(), locale)));
        }
        for (Map.Entry<UUID, BigDecimalField> entry : investedFieldsByAccount.entrySet()) {
            AccountSnapshot previous = reference == null
                    ? null
                    : previousInvestedSnapshot(entry.getKey(), reference);
            entry.getValue().setHelperText(previous == null
                    ? getTranslation("finance.balance.hint.invested-none")
                    : getTranslation("finance.balance.hint.previous",
                            MoneyFormatter.format(previous.getInvestedCapital(), locale),
                            DateFormatter.format(previous.getSnapshotDate(), locale)));
        }
    }

    /** {@code history} is ordered newest first. */
    private AccountSnapshot previousSnapshot(UUID accountId, LocalDate reference) {
        for (BalanceDateRow row : history) {
            if (row.date().isBefore(reference) && row.snapshotFor(accountId) != null) {
                return row.snapshotFor(accountId);
            }
        }
        return null;
    }

    private AccountSnapshot previousInvestedSnapshot(UUID accountId, LocalDate reference) {
        for (BalanceDateRow row : history) {
            AccountSnapshot snap = row.snapshotFor(accountId);
            if (row.date().isBefore(reference) && snap != null && snap.getInvestedCapital() != null) {
                return snap;
            }
        }
        return null;
    }

    private void save() {
        if (date.getValue() == null) {
            Notification.show(getTranslation("finance.balance.error.date-required"), 4000,
                    Notification.Position.MIDDLE);
            return;
        }
        Map<UUID, BigDecimal> values = new LinkedHashMap<>();
        fieldsByAccount.forEach((accountId, field) -> values.put(accountId, field.getValue()));
        Map<UUID, BigDecimal> investedValues = new LinkedHashMap<>();
        investedFieldsByAccount.forEach((accountId, field) -> investedValues.put(accountId, field.getValue()));
        try {
            int saved = snapshotService.recordBatch(date.getValue(), values, investedValues);
            Notification.show(getTranslation("finance.balance.notification.saved", saved), 3000,
                    Notification.Position.BOTTOM_START);
            close();
            if (onSaved != null) {
                onSaved.run();
            }
        } catch (IllegalArgumentException exception) {
            Notification.show(getTranslation("finance.notification.error", exception.getMessage()), 5000,
                    Notification.Position.MIDDLE);
        }
    }

    void saveForTest() {
        save();
    }

    Map<UUID, BigDecimalField> getFieldsByAccountForTest() {
        return fieldsByAccount;
    }

    Map<UUID, BigDecimalField> getInvestedFieldsByAccountForTest() {
        return investedFieldsByAccount;
    }

    private static Locale locale() {
        return UI.getCurrent() != null ? UI.getCurrent().getLocale() : Locale.GERMANY;
    }
}
