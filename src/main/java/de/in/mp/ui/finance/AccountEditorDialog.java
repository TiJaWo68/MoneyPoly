package de.in.mp.ui.finance;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
import de.in.mp.finance.BankLookupService;

/**
 * Modal editor for creating and updating a single {@link Account}.
 * <p>
 * Entering a German IBAN pre-fills the bank name via {@link BankLookupService}
 * as long as the bank field is still empty. The bank field stays fully
 * editable afterwards.
 */
public class AccountEditorDialog extends Dialog {

    private static final int NAME_MAX_LENGTH = 100;

    private final AccountService accountService;
    private final BankLookupService bankLookupService;

    private final Binder<Account> binder = new Binder<>(Account.class);

    private final TextField name = new TextField();
    private final ComboBox<AccountType> accountType = new ComboBox<>();
    private final ComboBox<Account> parent = new ComboBox<>();
    private final TextField accountNumber = new TextField();
    private final TextField bankName = new TextField();
    private final BigDecimalField initialBalance = new BigDecimalField();
    private final TextArea description = new TextArea();

    private final Button saveButton = new Button();
    private final Button cancelButton = new Button();

    private Account currentAccount;
    private Runnable onSaved;
    private List<Account> selectableParents = List.of();

    public AccountEditorDialog(AccountService accountService, BankLookupService bankLookupService) {
        this.accountService = accountService;
        this.bankLookupService = bankLookupService;

        configureFields();
        configureBinder();

        accountType.addValueChangeListener(event -> updateFieldVisibility());
        accountNumber.addValueChangeListener(event -> prefillBankName(event.getValue()));

        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveButton.addClickListener(event -> save());
        cancelButton.addClickListener(event -> close());

        FormLayout form = new FormLayout(name, accountType, parent, accountNumber, bankName, initialBalance, description);
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
        parent.clear();
        refreshParentItems();
        updateFieldVisibility();
        open();
    }

    public void openForEdit(Account account, Runnable onSaved) {
        this.onSaved = onSaved;
        this.currentAccount = account;
        setHeaderTitle(getTranslation("finance.editor.title.edit"));
        binder.readBean(account);
        refreshParentItems();
        parent.setValue(resolveParent(account.getParentId()));
        updateFieldVisibility();
        open();
    }

    private void configureFields() {
        name.setLabel(getTranslation("finance.editor.field.name"));
        accountType.setLabel(getTranslation("finance.editor.field.type"));
        parent.setLabel(getTranslation("finance.editor.field.parent"));
        accountNumber.setLabel(getTranslation("finance.editor.field.account-number"));
        bankName.setLabel(getTranslation("finance.editor.field.bank"));
        initialBalance.setLabel(getTranslation("finance.editor.field.initial-balance"));
        description.setLabel(getTranslation("finance.editor.field.note"));
        saveButton.setText(getTranslation("finance.editor.button.save"));
        cancelButton.setText(getTranslation("finance.editor.button.cancel"));

        accountType.setItems(AccountType.values());
        accountType.setItemLabelGenerator(this::translateAccountType);

        parent.setItemLabelGenerator(this::translateParent);
        parent.setClearButtonVisible(true);
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

    private String translateParent(Account account) {
        return account.getName() + " (" + translateAccountType(account.getAccountType()) + ")";
    }

    private void refreshParentItems() {
        List<Account> accounts = accountService.findAll();
        Set<UUID> excluded = collectSelfAndDescendants(currentAccount.getId(), accounts);
        selectableParents = accounts.stream()
                .filter(account -> account.getId() != null && !excluded.contains(account.getId()))
                .collect(Collectors.toList());
        parent.setItems(selectableParents);
    }

    private Set<UUID> collectSelfAndDescendants(UUID rootId, List<Account> accounts) {
        Set<UUID> excluded = new HashSet<>();
        if (rootId == null) {
            return excluded;
        }
        excluded.add(rootId);

        Map<UUID, List<Account>> childrenByParent = accounts.stream()
                .filter(account -> account.getParentId() != null)
                .collect(Collectors.groupingBy(Account::getParentId));

        Deque<UUID> stack = new ArrayDeque<>();
        stack.push(rootId);
        while (!stack.isEmpty()) {
            UUID current = stack.pop();
            for (Account child : childrenByParent.getOrDefault(current, List.of())) {
                if (excluded.add(child.getId())) {
                    stack.push(child.getId());
                }
            }
        }
        return excluded;
    }

    private Account resolveParent(UUID parentId) {
        if (parentId == null) {
            return null;
        }
        return selectableParents.stream()
                .filter(account -> parentId.equals(account.getId()))
                .findFirst()
                .orElse(null);
    }

    private void updateFieldVisibility() {
        boolean institution = AccountType.INSTITUTION.equals(accountType.getValue());
        accountNumber.setVisible(!institution);
        bankName.setVisible(!institution);
        initialBalance.setVisible(!institution);
        if (institution) {
            initialBalance.clear();
        }
    }

    private void prefillBankName(String iban) {
        if (iban == null || iban.isBlank()) {
            return;
        }
        if (bankName.getValue() != null && !bankName.getValue().isBlank()) {
            return;
        }
        bankLookupService.findBankName(iban).ifPresent(bankName::setValue);
    }

    private void save() {
        if (!binder.writeBeanIfValid(currentAccount)) {
            return;
        }

        Account selectedParent = parent.getValue();
        currentAccount.setParentId(selectedParent == null ? null : selectedParent.getId());

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
