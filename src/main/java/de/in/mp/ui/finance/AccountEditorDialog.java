package de.in.mp.ui.finance;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.binder.Binder;

import de.in.mp.finance.Account;
import de.in.mp.finance.AccountService;
import de.in.mp.finance.AccountType;

/**
 * Modal editor for creating and updating a single {@link Account}.
 */
public class AccountEditorDialog extends Dialog {

    private static final int NAME_MAX_LENGTH = 100;

    private final AccountService accountService;

    private final Binder<Account> binder = new Binder<>(Account.class);

    private final TextField name = new TextField();
    private final ComboBox<AccountType> accountType = new ComboBox<>();
    private final TextField accountNumber = new TextField();
    private final TextField bankName = new TextField();
    private final BigDecimalField initialBalance = new BigDecimalField();
    private final TextArea description = new TextArea();

    private final Button saveButton = new Button();
    private final Button cancelButton = new Button();

    private Account currentAccount;
    private Runnable onSaved;

    public AccountEditorDialog(AccountService accountService) {
        this.accountService = accountService;

        configureFields();
        configureBinder();

        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveButton.addClickListener(event -> save());
        cancelButton.addClickListener(event -> close());

        FormLayout form = new FormLayout(name, accountType, accountNumber, bankName, initialBalance, description);
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1));

        HorizontalLayout actions = new HorizontalLayout(cancelButton, saveButton);
        actions.setWidthFull();
        actions.setJustifyContentMode(JustifyContentMode.END);

        add(form, actions);
        setCloseOnEsc(true);
        setCloseOnOutsideClick(false);
    }

    public void openForCreate(Runnable onSaved) {
        this.onSaved = onSaved;
        this.currentAccount = new Account();
        setHeaderTitle(getTranslation("finance.editor.title.new"));
        binder.readBean(currentAccount);
        open();
    }

    public void openForEdit(Account account, Runnable onSaved) {
        this.onSaved = onSaved;
        this.currentAccount = account;
        setHeaderTitle(getTranslation("finance.editor.title.edit"));
        binder.readBean(account);
        open();
    }

    private void configureFields() {
        name.setLabel(getTranslation("finance.editor.field.name"));
        accountType.setLabel(getTranslation("finance.editor.field.type"));
        accountNumber.setLabel(getTranslation("finance.editor.field.account-number"));
        bankName.setLabel(getTranslation("finance.editor.field.bank"));
        initialBalance.setLabel(getTranslation("finance.editor.field.initial-balance"));
        description.setLabel(getTranslation("finance.editor.field.note"));
        saveButton.setText(getTranslation("finance.editor.button.save"));
        cancelButton.setText(getTranslation("finance.editor.button.cancel"));

        accountType.setItems(AccountType.values());
        accountType.setItemLabelGenerator(this::translateAccountType);
    }

    private void configureBinder() {
        binder.forField(name)
                .asRequired(getTranslation("finance.editor.error.name-required"))
                .withValidator(value -> value.length() <= NAME_MAX_LENGTH,
                        getTranslation("finance.editor.error.name-too-long"))
                .bind(Account::getName, Account::setName);

        binder.forField(accountType)
                .asRequired(getTranslation("finance.editor.error.type-required"))
                .bind(Account::getAccountType, Account::setAccountType);

        binder.forField(accountNumber)
                .bind(Account::getAccountNumber, Account::setAccountNumber);

        binder.forField(bankName)
                .bind(Account::getBankName, Account::setBankName);

        binder.forField(initialBalance)
                .bind(Account::getInitialBalance, Account::setInitialBalance);

        binder.forField(description)
                .bind(Account::getDescription, Account::setDescription);
    }

    private String translateAccountType(AccountType type) {
        return getTranslation("finance.account.type." + type.name());
    }

    private void save() {
        if (!binder.writeBeanIfValid(currentAccount)) {
            return;
        }

        try {
            accountService.save(currentAccount);
            close();
            if (onSaved != null) {
                onSaved.run();
            }
        } catch (IllegalArgumentException exception) {
            Notification.show(getTranslation("finance.notification.error", exception.getMessage()), 5000,
                    Notification.Position.MIDDLE);
        }
    }
}
