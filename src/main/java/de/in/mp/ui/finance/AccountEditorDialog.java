package de.in.mp.ui.finance;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.Autocomplete;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.binder.Binder;
import com.vaadin.flow.data.binder.ValidationResult;
import com.vaadin.flow.theme.lumo.LumoUtility;

import de.in.mp.finance.Account;
import de.in.mp.finance.AccountService;
import de.in.mp.finance.AccountType;
import de.in.mp.finance.BankLookupService;
import de.in.mp.finance.banksync.BankConnection;
import de.in.mp.finance.banksync.BankConnectionStatus;
import de.in.mp.finance.banksync.BankSyncProvider;
import de.in.mp.finance.banksync.BankSyncService;
import de.in.mp.finance.banksync.ExternalBankAccount;
import de.in.mp.i18n.MoneyPolyI18nProvider;

/**
 * Modal editor for creating and updating a single {@link Account}.
 * <p>
 * Entering a German IBAN pre-fills the bank name via {@link BankLookupService}
 * as long as the bank field is still empty. The bank field stays fully
 * editable afterwards. The account holder is used to match bank statements
 * of sub-accounts that carry no IBAN.
 */
public class AccountEditorDialog extends Dialog {

    private static final int NAME_MAX_LENGTH = 100;
    private static final int HOLDER_MAX_LENGTH = 100;
    private static final int NOTIFICATION_DURATION_MS = 5000;
    private static final String VALIDATION_MESSAGE_SEPARATOR = ", ";

    private final AccountService accountService;
    private final BankLookupService bankLookupService;
    private final BankSyncService bankSyncService;
    private final BiConsumer<String, UUID> onManageConnections;

    private final Binder<Account> binder = new Binder<>(Account.class);

    final TextField name = new TextField();
    final ComboBox<AccountType> accountType = new ComboBox<>();
    private final ComboBox<Account> parent = new ComboBox<>();
    private final TextField accountNumber = new TextField();
    private final TextField bankName = new TextField();
    private final TextField accountHolder = new TextField();
    final BigDecimalField promotionalInterestRate = new BigDecimalField();
    final DatePicker promotionalUntil = new DatePicker();
    final BigDecimalField subsequentInterestRate = new BigDecimalField();
    private final BigDecimalField initialBalance = new BigDecimalField();
    private final IntegerField displayOrder = new IntegerField();
    final H4 openBankingHeading = new H4();
    final ComboBox<BankConnection> bankConnection = new ComboBox<>();
    final Button manageConnectionsButton = new Button();
    final TextField externalAccountId = new TextField();
    final Checkbox autoSyncEnabled = new Checkbox();
    private final TextArea description = new TextArea();

    private final Button saveButton = new Button();
    private final Button cancelButton = new Button();

    private Account currentAccount;
    private Runnable onSaved;
    private List<Account> selectableParents = List.of();
    private List<BankConnection> selectableConnections = List.of();

    public AccountEditorDialog(AccountService accountService, BankLookupService bankLookupService) {
        this(accountService, bankLookupService, null, (BiConsumer<String, UUID>) null);
    }

    public AccountEditorDialog(
            AccountService accountService,
            BankLookupService bankLookupService,
            BankSyncService bankSyncService,
            Runnable onManageConnections) {
        this(accountService, bankLookupService, bankSyncService,
                onManageConnections == null ? null : (bankHint, accountId) -> onManageConnections.run());
    }

    public AccountEditorDialog(
            AccountService accountService,
            BankLookupService bankLookupService,
            BankSyncService bankSyncService,
            BiConsumer<String, UUID> onManageConnections) {
        this.accountService = accountService;
        this.bankLookupService = bankLookupService;
        this.bankSyncService = bankSyncService;
        this.onManageConnections = onManageConnections;

        configureFields();
        configureBinder();

        accountType.addValueChangeListener(event -> updateFieldVisibility());
        accountNumber.addValueChangeListener(event -> prefillBankName(event.getValue()));
        bankConnection.addValueChangeListener(event -> onBankConnectionChanged(event.getValue()));

        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveButton.addClickListener(event -> save());
        cancelButton.addClickListener(event -> close());

        FormLayout form = new FormLayout(name, accountType, parent, accountNumber, bankName, accountHolder,
                promotionalInterestRate, promotionalUntil, subsequentInterestRate,
                initialBalance, displayOrder,
                openBankingHeading, bankConnection, manageConnectionsButton, externalAccountId, autoSyncEnabled,
                description);
        form.setResponsiveSteps(
                new FormLayout.ResponsiveStep("0", 1),
                new FormLayout.ResponsiveStep("500px", 2));
        form.setColspan(openBankingHeading, 2);
        form.setColspan(autoSyncEnabled, 2);
        form.setColspan(description, 2);

        HorizontalLayout actions = new HorizontalLayout(cancelButton, saveButton);
        actions.setWidthFull();
        actions.setJustifyContentMode(JustifyContentMode.END);

        setWidth("640px");
        add(form, actions);
        setCloseOnEsc(true);
        setCloseOnOutsideClick(false);
    }

    public void openForCreate(Runnable onSaved) {
        initForCreate(onSaved);
        open();
    }

    void initForCreate(Runnable onSaved) {
        this.onSaved = onSaved;
        this.currentAccount = new Account();
        setHeaderTitle(getTranslation("finance.editor.title.new"));
        binder.readBean(currentAccount);
        parent.clear();
        refreshParentItems();
        refreshBankConnections(null);
        bankConnection.clear();
        updateFieldVisibility();
    }

    public void openForEdit(Account account, Runnable onSaved) {
        this.onSaved = onSaved;
        this.currentAccount = account;
        setHeaderTitle(getTranslation("finance.editor.title.edit"));
        binder.readBean(account);
        refreshParentItems();
        parent.setValue(resolveParent(account.getParentId()));
        refreshBankConnections(account.getHouseholdId());
        bankConnection.setValue(resolveBankConnection(account.getBankConnectionId()));
        updateFieldVisibility();
        open();
    }

    private void configureFields() {
        name.setLabel(getTranslation("finance.editor.field.name"));
        accountType.setLabel(getTranslation("finance.editor.field.type"));
        parent.setLabel(getTranslation("finance.editor.field.parent"));
        accountNumber.setLabel(getTranslation("finance.editor.field.account-number"));
        accountNumber.setAutocomplete(Autocomplete.OFF);
        bankName.setLabel(getTranslation("finance.editor.field.bank"));
        accountHolder.setLabel(getTranslation("finance.editor.field.holder"));
        accountHolder.setAutocomplete(Autocomplete.OFF);
        accountHolder.setHelperText(getTranslation("finance.editor.hint.holder"));
        promotionalInterestRate.setLabel(getTranslation("finance.editor.field.promotional-interest-rate"));
        promotionalInterestRate.setSuffixComponent(new Span("%"));
        promotionalUntil.setLabel(getTranslation("finance.editor.field.promotional-until"));
        promotionalUntil.setClearButtonVisible(true);
        subsequentInterestRate.setLabel(getTranslation("finance.editor.field.subsequent-interest-rate"));
        subsequentInterestRate.setSuffixComponent(new Span("%"));
        initialBalance.setLabel(getTranslation("finance.editor.field.initial-balance"));
        displayOrder.setLabel(getTranslation("finance.editor.field.display-order"));
        openBankingHeading.setText(translateBankSync("finance.banksync.editor.heading"));
        openBankingHeading.addClassNames(LumoUtility.FontSize.MEDIUM, LumoUtility.Margin.Top.SMALL,
                LumoUtility.Margin.Bottom.NONE);
        bankConnection.setLabel(translateBankSync("finance.banksync.field.connection"));
        bankConnection.setPlaceholder(translateBankSync("finance.banksync.editor.connection.placeholder"));
        bankConnection.setHelperText(translateBankSync("finance.banksync.editor.connection.helper"));
        bankConnection.setTooltipText(translateBankSync("finance.banksync.editor.connection.tooltip"));
        bankConnection.setClearButtonVisible(true);
        bankConnection.setItemLabelGenerator(conn -> conn.getInstitutionName()
                + " (" + translateBankSync("finance.banksync.status." + conn.getStatus().name()) + ")");
        manageConnectionsButton.setText(translateBankSync("finance.banksync.button.manage-in-editor"));
        manageConnectionsButton.setTooltipText(translateBankSync("finance.banksync.editor.manage.tooltip"));
        manageConnectionsButton.setIcon(VaadinIcon.CONNECT.create());
        manageConnectionsButton.addClickListener(event -> {
            if (onManageConnections != null) {
                UUID accountId = currentAccount != null ? currentAccount.getId() : null;
                onManageConnections.accept(resolveBankHint(), accountId);
            }
        });
        externalAccountId.setLabel(translateBankSync("finance.banksync.field.external-account-id"));
        externalAccountId.setPlaceholder(translateBankSync("finance.banksync.editor.external-id.placeholder"));
        externalAccountId.setHelperText(translateBankSync("finance.banksync.editor.external-id.helper"));
        externalAccountId.setTooltipText(translateBankSync("finance.banksync.editor.external-id.tooltip"));
        externalAccountId.setAutocomplete(Autocomplete.OFF);
        autoSyncEnabled.setLabel(translateBankSync("finance.banksync.field.auto-sync"));
        autoSyncEnabled.setTooltipText(translateBankSync("finance.banksync.editor.auto-sync.tooltip"));
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
                .withValidator(
                        value -> !AccountType.INSTITUTION.equals(accountType.getValue())
                                || (value != null && !value.isBlank()),
                        getTranslation("finance.editor.error.name-required"))
                .withValidator(value -> value == null || value.length() <= NAME_MAX_LENGTH,
                        getTranslation("finance.editor.error.name-too-long"))
                .bind(Account::getName, Account::setName);

        binder.forField(accountType)
                .asRequired(getTranslation("finance.editor.error.type-required"))
                .bind(Account::getAccountType, Account::setAccountType);

        binder.forField(accountNumber)
                .bind(Account::getAccountNumber, Account::setAccountNumber);

        binder.forField(bankName)
                .bind(Account::getBankName, Account::setBankName);

        binder.forField(accountHolder)
                .withValidator(value -> value == null || value.length() <= HOLDER_MAX_LENGTH,
                        getTranslation("finance.editor.error.holder-too-long"))
                .bind(Account::getAccountHolder, Account::setAccountHolder);

        binder.forField(promotionalInterestRate)
                .withValidator(value -> value == null || value.compareTo(BigDecimal.ZERO) >= 0,
                        getTranslation("finance.editor.error.interest-negative"))
                .bind(Account::getPromotionalInterestRate, Account::setPromotionalInterestRate);

        binder.forField(promotionalUntil)
                .bind(Account::getPromotionalUntil, Account::setPromotionalUntil);

        binder.forField(subsequentInterestRate)
                .withValidator(value -> value == null || value.compareTo(BigDecimal.ZERO) >= 0,
                        getTranslation("finance.editor.error.interest-negative"))
                .bind(Account::getSubsequentInterestRate, Account::setSubsequentInterestRate);

        binder.forField(initialBalance)
                .bind(Account::getInitialBalance, Account::setInitialBalance);

        binder.forField(displayOrder)
                .<Integer>withConverter(
                        value -> value == null ? Account.DEFAULT_DISPLAY_ORDER : value,
                        value -> value)
                .bind(Account::getDisplayOrder, Account::setDisplayOrder);

        binder.forField(externalAccountId)
                .bind(Account::getExternalAccountId, Account::setExternalAccountId);

        binder.forField(autoSyncEnabled)
                .bind(Account::isAutoSyncEnabled, Account::setAutoSyncEnabled);

        binder.forField(description)
                .bind(Account::getDescription, Account::setDescription);
    }

    private String translateAccountType(AccountType type) {
        return getTranslation("finance.account.type." + type.name());
    }

    private String translateParent(Account account) {
        if (account.getAccountType() == AccountType.INSTITUTION) {
            return account.getName();
        }
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

    public void refreshBankConnections(UUID householdId) {
        if (bankSyncService == null) {
            selectableConnections = List.of();
            bankConnection.setItems(List.of());
            return;
        }
        UUID previousId = bankConnection.getValue() != null ? bankConnection.getValue().getId() : null;
        selectableConnections = householdId != null
                ? bankSyncService.findConnectionsByHousehold(householdId)
                : bankSyncService.findConnectionsForCurrentHousehold();
        bankConnection.setItems(selectableConnections);
        if (previousId != null) {
            bankConnection.setValue(resolveBankConnection(previousId));
        }
    }

    private BankConnection resolveBankConnection(UUID connectionId) {
        if (connectionId == null) {
            return null;
        }
        return selectableConnections.stream()
                .filter(conn -> connectionId.equals(conn.getId()))
                .findFirst()
                .orElse(null);
    }

    private void onBankConnectionChanged(BankConnection selected) {
        if (bankSyncService == null || selected == null) {
            autoSyncEnabled.setValue(false);
            autoSyncEnabled.setEnabled(false);
            return;
        }
        boolean supportsBg = bankSyncService.findProvider(selected.getProviderId())
                .map(BankSyncProvider::supportsBackgroundSync)
                .orElse(false);
        autoSyncEnabled.setEnabled(supportsBg);
        if (!supportsBg) {
            autoSyncEnabled.setValue(false);
        }
        if ((externalAccountId.getValue() == null || externalAccountId.getValue().isBlank())
                && selected.getStatus() == BankConnectionStatus.ACTIVE) {
            prefillExternalAccountId(selected);
        }
    }

    private void prefillExternalAccountId(BankConnection connection) {
        String normalizedIban = normalizeIban(accountNumber.getValue());
        if (normalizedIban == null) {
            return;
        }
        try {
            List<ExternalBankAccount> externalAccounts = bankSyncService.fetchExternalAccounts(connection.getId());
            List<ExternalBankAccount> matches = externalAccounts.stream()
                    .filter(ext -> normalizedIban.equals(normalizeIban(ext.iban())))
                    .toList();
            if (matches.size() == 1) {
                externalAccountId.setValue(matches.get(0).externalAccountId());
            }
        } catch (RuntimeException ignored) {
            // Leave external account ID editable manually when discovery fails.
        }
    }

    private static String normalizeIban(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private String translateBankSync(String key, Object... params) {
        String translation = getTranslation(key, params);
        if (translation != null && translation.startsWith("!{") && translation.endsWith("}!")) {
            Locale locale = UI.getCurrent() != null ? UI.getCurrent().getLocale() : Locale.GERMANY;
            return new MoneyPolyI18nProvider().getTranslation(key, locale, params);
        }
        return translation;
    }

    private void updateFieldVisibility() {
        boolean institution = AccountType.INSTITUTION.equals(accountType.getValue());
        boolean savings = AccountType.SAVINGS.equals(accountType.getValue());
        boolean openBankingAvailable = bankSyncService != null;
        name.setVisible(institution);
        parent.setVisible(!institution);
        accountNumber.setVisible(!institution);
        accountHolder.setVisible(!institution);
        initialBalance.setVisible(!institution);
        bankName.setVisible(false);

        promotionalInterestRate.setVisible(savings);
        promotionalUntil.setVisible(savings);
        subsequentInterestRate.setVisible(savings);

        openBankingHeading.setVisible(openBankingAvailable);
        manageConnectionsButton.setVisible(openBankingAvailable && onManageConnections != null);
        bankConnection.setVisible(openBankingAvailable && !institution);
        externalAccountId.setVisible(openBankingAvailable && !institution);
        autoSyncEnabled.setVisible(openBankingAvailable && !institution);

        if (institution) {
            parent.clear();
            initialBalance.clear();
            bankConnection.clear();
            externalAccountId.clear();
            autoSyncEnabled.setValue(false);
        } else if (openBankingAvailable) {
            onBankConnectionChanged(bankConnection.getValue());
        }
        if (!savings) {
            promotionalInterestRate.clear();
            promotionalUntil.clear();
            subsequentInterestRate.clear();
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

    String validationSummary() {
        return binder.validate().getValidationErrors().stream()
                .map(ValidationResult::getErrorMessage)
                .collect(Collectors.joining(VALIDATION_MESSAGE_SEPARATOR));
    }

    void save() {
        if (!binder.writeBeanIfValid(currentAccount)) {
            if (com.vaadin.flow.component.UI.getCurrent() != null) {
                Notification.show(getTranslation("finance.editor.error.validation", validationSummary()),
                        NOTIFICATION_DURATION_MS, Notification.Position.MIDDLE);
            }
            return;
        }

        Account selectedParent = parent.getValue();
        currentAccount.setParentId(selectedParent == null ? null : selectedParent.getId());

        if (currentAccount.getAccountType() != AccountType.INSTITUTION) {
            currentAccount.setName(translateAccountType(currentAccount.getAccountType()));
            if (selectedParent != null) {
                currentAccount.setBankName(selectedParent.getName());
            } else if (currentAccount.getBankName() == null || currentAccount.getBankName().isBlank()) {
                bankLookupService.findBankName(currentAccount.getAccountNumber())
                        .ifPresent(currentAccount::setBankName);
            }
            if (bankSyncService != null) {
                BankConnection selectedConnection = bankConnection.getValue();
                currentAccount.setBankConnectionId(selectedConnection == null ? null : selectedConnection.getId());
                String extId = currentAccount.getExternalAccountId();
                currentAccount.setExternalAccountId(extId == null || extId.isBlank() ? null : extId.trim());
                if (selectedConnection == null) {
                    currentAccount.setAutoSyncEnabled(false);
                }
            }
        } else {
            currentAccount.setBankName(currentAccount.getName());
        }

        try {
            accountService.save(currentAccount);
            close();
            if (onSaved != null) {
                onSaved.run();
            }
        } catch (IllegalArgumentException exception) {
            Notification.show(getTranslation("finance.notification.error", exception.getMessage()),
                    NOTIFICATION_DURATION_MS,
                    Notification.Position.MIDDLE);
        }
    }

    String resolveBankHint() {
        String directBank = bankName.getValue();
        if (directBank != null && !directBank.isBlank()) {
            return directBank.trim();
        }
        Account selectedParent = parent.getValue();
        if (selectedParent != null) {
            Map<UUID, Account> parentsById = selectableParents.stream()
                    .filter(a -> a != null && a.getId() != null)
                    .collect(Collectors.toMap(Account::getId, a -> a, (a, b) -> a));
            Set<UUID> visited = new HashSet<>();
            Account cursor = selectedParent;
            while (cursor != null) {
                if (cursor.getAccountType() == AccountType.INSTITUTION
                        && cursor.getName() != null && !cursor.getName().isBlank()) {
                    return cursor.getName().trim();
                }
                UUID parentId = cursor.getParentId();
                if (parentId == null || !visited.add(parentId)) {
                    break;
                }
                cursor = parentsById.get(parentId);
            }
            if (selectedParent.getName() != null && !selectedParent.getName().isBlank()) {
                return selectedParent.getName().trim();
            }
        }
        if (AccountType.INSTITUTION.equals(accountType.getValue())
                && name.getValue() != null && !name.getValue().isBlank()) {
            return name.getValue().trim();
        }
        return null;
    }
}
