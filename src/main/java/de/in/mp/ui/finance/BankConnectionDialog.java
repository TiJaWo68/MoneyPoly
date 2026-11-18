package de.in.mp.ui.finance;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.UUID;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.theme.lumo.LumoUtility;

import de.in.mp.finance.Account;
import de.in.mp.finance.AccountService;
import de.in.mp.finance.AccountType;
import de.in.mp.finance.banksync.AuthorizedSession;
import de.in.mp.finance.banksync.BankConnection;
import de.in.mp.finance.banksync.BankConnectionStatus;
import de.in.mp.finance.banksync.BankInstitution;
import de.in.mp.finance.banksync.BankSyncProvider;
import de.in.mp.finance.banksync.BankSyncResult;
import de.in.mp.finance.banksync.BankSyncService;
import de.in.mp.finance.banksync.ConnectionInitiationResult;
import de.in.mp.finance.banksync.ExternalBankAccount;
import de.in.mp.i18n.MoneyPolyI18nProvider;
import de.in.mp.ui.GridActionButton;

/**
 * Modal dialog for managing read-only Open-Banking connections
 * ({@link BankConnection}), completing OAuth2/redirect authorizations, inspecting
 * PSD2 consent validity and linking MoneyPoly accounts.
 */
class BankConnectionDialog extends Dialog {

    private static final String DIALOG_WIDTH = "960px";
    private static final String DIALOG_HEIGHT = "720px";
    private static final int NOTIFICATION_DURATION_MS = 4000;
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final BankSyncService bankSyncService;
    private final AccountService accountService;

    private final Span cryptoWarning = new Span();
    private final ComboBox<BankSyncProvider> providerBox = new ComboBox<>();
    private final ComboBox<BankInstitution> institutionBox = new ComboBox<>();
    private final TextField callbackUrlField = new TextField();
    private final Button connectButton = new Button();

    private final VerticalLayout pendingAuthPanel = new VerticalLayout();
    private final Anchor redirectLink = new Anchor();
    private final TextField authCodeField = new TextField();
    private final Button completeAuthButton = new Button();

    private final Grid<BankConnection> connectionGrid = new Grid<>(BankConnection.class, false);

    private final ComboBox<Account> accountBox = new ComboBox<>();
    private final ComboBox<BankConnection> linkConnectionBox = new ComboBox<>();
    private final ComboBox<ExternalBankAccount> externalAccountBox = new ComboBox<>();
    private final TextField externalAccountIdField = new TextField();
    private final Checkbox autoSyncCheckbox = new Checkbox();
    private final Button linkButton = new Button();
    private final Button unlinkButton = new Button();

    private UUID householdId;
    private UUID pendingConnectionId;
    private Runnable onChanged;

    BankConnectionDialog(BankSyncService bankSyncService, AccountService accountService) {
        this.bankSyncService = bankSyncService;
        this.accountService = accountService;

        setHeaderTitle(getTranslation("finance.banksync.dialog.title"));
        setWidth(DIALOG_WIDTH);
        setMaxWidth("96vw");
        setHeight(DIALOG_HEIGHT);
        setMaxHeight("92vh");
        setCloseOnEsc(true);
        setCloseOnOutsideClick(false);

        configureComponents();
        buildLayout();
        buildFooter();
    }

    void openForHousehold(UUID householdId, Runnable onChanged) {
        initForHousehold(householdId, onChanged);
        open();
    }

    void initForHousehold(UUID householdId, Runnable onChanged) {
        this.householdId = householdId;
        this.onChanged = onChanged;
        this.pendingConnectionId = null;
        this.pendingAuthPanel.setVisible(false);
        this.redirectLink.setVisible(false);
        this.authCodeField.clear();
        refresh();
    }

    private void configureComponents() {
        cryptoWarning.setText(getTranslation("finance.banksync.warning.crypto-missing"));
        cryptoWarning.getElement().getThemeList().add("badge");
        cryptoWarning.getElement().getThemeList().add("error");
        cryptoWarning.setWidthFull();

        providerBox.setLabel(getTranslation("finance.banksync.field.provider"));
        providerBox.setTooltipText(getTranslation("finance.banksync.dialog.provider.tooltip"));
        providerBox.setItems(bankSyncService.getProviders());
        providerBox.setItemLabelGenerator(p -> p.isConfigured()
                ? p.displayName()
                : getTranslation("finance.banksync.provider.unconfigured", p.displayName()));
        providerBox.addValueChangeListener(e -> loadInstitutions(e.getValue()));

        institutionBox.setLabel(getTranslation("finance.banksync.field.institution"));
        institutionBox.setTooltipText(getTranslation("finance.banksync.dialog.institution.tooltip"));
        institutionBox.setItemLabelGenerator(BankInstitution::name);

        callbackUrlField.setLabel(getTranslation("finance.banksync.field.callback-url"));
        callbackUrlField.setTooltipText(getTranslation("finance.banksync.dialog.callback-url.tooltip"));
        callbackUrlField.setValue("http://localhost:8080/finance");

        connectButton.setText(getTranslation("finance.banksync.button.connect"));
        connectButton.setTooltipText(getTranslation("finance.banksync.dialog.connect.tooltip"));
        connectButton.setIcon(VaadinIcon.CONNECT.create());
        connectButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        connectButton.addClickListener(e -> startAuthorization());

        redirectLink.setText(getTranslation("finance.banksync.link.open-auth"));
        redirectLink.setTarget("_blank");
        redirectLink.setVisible(false);

        authCodeField.setLabel(getTranslation("finance.banksync.field.auth-code"));
        authCodeField.setPlaceholder(getTranslation("finance.banksync.dialog.auth-code.placeholder"));
        authCodeField.setTooltipText(getTranslation("finance.banksync.dialog.auth-code.tooltip"));
        authCodeField.setWidthFull();

        completeAuthButton.setText(getTranslation("finance.banksync.button.complete-auth"));
        completeAuthButton.setTooltipText(getTranslation("finance.banksync.dialog.complete-auth.tooltip"));
        completeAuthButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        completeAuthButton.addClickListener(e -> finishAuthorization());

        pendingAuthPanel.setPadding(true);
        pendingAuthPanel.addClassNames(LumoUtility.Background.CONTRAST_5, LumoUtility.BorderRadius.MEDIUM);
        pendingAuthPanel.setVisible(false);
        HorizontalLayout codeRow = new HorizontalLayout(authCodeField, completeAuthButton);
        codeRow.setWidthFull();
        codeRow.setDefaultVerticalComponentAlignment(Alignment.END);
        codeRow.expand(authCodeField);
        pendingAuthPanel.add(redirectLink, codeRow);

        configureConnectionGrid();
        configureLinkingSection();
    }

    private void configureConnectionGrid() {
        connectionGrid.addColumn(BankConnection::getInstitutionName)
                .setHeader(getTranslation("finance.banksync.column.institution"))
                .setAutoWidth(true);
        connectionGrid.addColumn(conn -> bankSyncService.findProvider(conn.getProviderId())
                        .map(BankSyncProvider::displayName)
                        .orElse(conn.getProviderId()))
                .setHeader(getTranslation("finance.banksync.column.provider"))
                .setAutoWidth(true);
        connectionGrid.addColumn(new ComponentRenderer<>(this::renderStatusBadge))
                .setHeader(getTranslation("finance.banksync.column.status"))
                .setAutoWidth(true);
        connectionGrid.addColumn(new ComponentRenderer<>(this::renderValidityBadge))
                .setHeader(getTranslation("finance.banksync.column.validity"))
                .setAutoWidth(true);
        connectionGrid.addColumn(conn -> conn.getLastSyncAt() == null
                        ? "–"
                        : conn.getLastSyncAt().format(TIMESTAMP_FORMAT))
                .setHeader(getTranslation("finance.banksync.column.last-sync"))
                .setAutoWidth(true);
        connectionGrid.addColumn(new ComponentRenderer<>(this::renderConnectionActions))
                .setHeader(getTranslation("finance.banksync.column.actions"))
                .setAutoWidth(true);
        connectionGrid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_COMPACT);
        connectionGrid.setHeight("210px");
    }

    private void configureLinkingSection() {
        accountBox.setLabel(getTranslation("finance.banksync.field.account"));
        accountBox.setTooltipText(getTranslation("finance.banksync.dialog.account.tooltip"));
        accountBox.setItemLabelGenerator(BankConnectionDialog::formatAccountLabel);
        accountBox.addValueChangeListener(e -> onAccountSelected(e.getValue()));

        linkConnectionBox.setLabel(getTranslation("finance.banksync.field.connection"));
        linkConnectionBox.setTooltipText(getTranslation("finance.banksync.editor.connection.tooltip"));
        linkConnectionBox.setItemLabelGenerator(BankConnection::getInstitutionName);
        linkConnectionBox.addValueChangeListener(e -> onLinkConnectionSelected(e.getValue()));

        externalAccountBox.setLabel(getTranslation("finance.banksync.field.external-account-select"));
        externalAccountBox.setTooltipText(getTranslation("finance.banksync.dialog.external-select.tooltip"));
        externalAccountBox.setItemLabelGenerator(ext -> ext.iban() != null && !ext.iban().isBlank()
                ? ext.name() + " (" + ext.iban() + ")"
                : ext.name() + " [" + ext.externalAccountId() + "]");
        externalAccountBox.addValueChangeListener(e -> {
            if (e.getValue() != null) {
                externalAccountIdField.setValue(e.getValue().externalAccountId());
            }
        });

        externalAccountIdField.setLabel(getTranslation("finance.banksync.field.external-account-id"));
        externalAccountIdField.setPlaceholder(getTranslation("finance.banksync.editor.external-id.placeholder"));
        externalAccountIdField.setTooltipText(getTranslation("finance.banksync.editor.external-id.tooltip"));
        autoSyncCheckbox.setLabel(getTranslation("finance.banksync.field.auto-sync"));
        autoSyncCheckbox.setTooltipText(getTranslation("finance.banksync.editor.auto-sync.tooltip"));

        linkButton.setText(getTranslation("finance.banksync.button.link"));
        linkButton.setTooltipText(getTranslation("finance.banksync.dialog.link.tooltip"));
        linkButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
        linkButton.addClickListener(e -> linkSelectedAccount());

        unlinkButton.setText(getTranslation("finance.banksync.button.unlink"));
        unlinkButton.setTooltipText(getTranslation("finance.banksync.dialog.unlink.tooltip"));
        unlinkButton.addThemeVariants(ButtonVariant.LUMO_SMALL);
        unlinkButton.addClickListener(e -> unlinkSelectedAccount());
    }

    private void buildLayout() {
        Paragraph subtitle = new Paragraph(getTranslation("finance.banksync.dialog.subtitle"));
        subtitle.addClassNames(LumoUtility.TextColor.SECONDARY, LumoUtility.Margin.Top.NONE);

        H4 newSectionTitle = new H4(getTranslation("finance.banksync.section.new-connection"));
        newSectionTitle.addClassNames(LumoUtility.Margin.Bottom.XSMALL, LumoUtility.Margin.Top.SMALL);

        FormLayout connectForm = new FormLayout(providerBox, institutionBox, callbackUrlField);
        connectForm.setResponsiveSteps(
                new FormLayout.ResponsiveStep("0", 1),
                new FormLayout.ResponsiveStep("600px", 3));

        HorizontalLayout connectActions = new HorizontalLayout(connectButton);
        connectActions.setWidthFull();

        H4 listTitle = new H4(getTranslation("finance.banksync.section.connections"));
        listTitle.addClassNames(LumoUtility.Margin.Bottom.XSMALL, LumoUtility.Margin.Top.MEDIUM);

        H4 linkTitle = new H4(getTranslation("finance.banksync.section.linking"));
        linkTitle.addClassNames(LumoUtility.Margin.Bottom.XSMALL, LumoUtility.Margin.Top.MEDIUM);

        FormLayout linkForm = new FormLayout(
                accountBox, linkConnectionBox, externalAccountBox, externalAccountIdField);
        linkForm.setResponsiveSteps(
                new FormLayout.ResponsiveStep("0", 1),
                new FormLayout.ResponsiveStep("600px", 2));

        HorizontalLayout linkActions = new HorizontalLayout(autoSyncCheckbox, linkButton, unlinkButton);
        linkActions.setWidthFull();
        linkActions.setDefaultVerticalComponentAlignment(Alignment.CENTER);
        linkActions.expand(autoSyncCheckbox);

        VerticalLayout content = new VerticalLayout(
                subtitle,
                cryptoWarning,
                newSectionTitle,
                connectForm,
                connectActions,
                pendingAuthPanel,
                listTitle,
                connectionGrid,
                linkTitle,
                linkForm,
                linkActions);
        content.setPadding(false);
        content.setSpacing(true);
        add(content);
    }

    private void buildFooter() {
        Button closeButton = new Button(getTranslation("ui.dialog.close"), e -> close());
        HorizontalLayout footer = new HorizontalLayout(closeButton);
        footer.setWidthFull();
        footer.setJustifyContentMode(JustifyContentMode.END);
        getFooter().add(footer);
    }

    private void refresh() {
        boolean cryptoReady = bankSyncService.isEncryptionConfigured();
        cryptoWarning.setVisible(!cryptoReady);
        connectButton.setEnabled(cryptoReady);

        if (providerBox.getValue() == null && !bankSyncService.getProviders().isEmpty()) {
            providerBox.setValue(bankSyncService.getProviders().get(0));
        } else {
            loadInstitutions(providerBox.getValue());
        }

        List<BankConnection> connections = bankSyncService.findConnectionsByHousehold(householdId);
        connectionGrid.setItems(connections);
        linkConnectionBox.setItems(connections);

        List<Account> accounts = (householdId == null
                ? accountService.findActiveAccounts()
                : accountService.findActiveAccountsByHousehold(householdId))
                .stream()
                .filter(a -> a.getAccountType() != AccountType.INSTITUTION)
                .toList();
        accountBox.setItems(accounts);
    }

    private void loadInstitutions(BankSyncProvider provider) {
        if (provider == null) {
            institutionBox.setItems(List.of());
            return;
        }
        try {
            List<BankInstitution> institutions = provider.listInstitutions("DE");
            institutionBox.setItems(institutions);
            if (!institutions.isEmpty()) {
                institutionBox.setValue(institutions.get(0));
            }
        } catch (RuntimeException ex) {
            institutionBox.setItems(List.of());
            showError(ex.getMessage());
        }
    }

    private void startAuthorization() {
        BankSyncProvider provider = providerBox.getValue();
        BankInstitution institution = institutionBox.getValue();
        if (provider == null || institution == null) {
            return;
        }
        try {
            ConnectionInitiationResult result = bankSyncService.initiateConnection(
                    householdId,
                    provider.providerId(),
                    institution,
                    callbackUrlField.getValue());
            pendingConnectionId = result.connection().getId();
            redirectLink.setHref(result.redirectUrl());
            redirectLink.setVisible(true);
            pendingAuthPanel.setVisible(true);
            authCodeField.clear();
            refresh();
        } catch (RuntimeException ex) {
            showError(ex.getMessage());
        }
    }

    private void finishAuthorization() {
        if (pendingConnectionId == null) {
            return;
        }
        try {
            AuthorizedSession session = bankSyncService.completeConnection(
                    pendingConnectionId, authCodeField.getValue());
            pendingAuthPanel.setVisible(false);
            pendingConnectionId = null;
            authCodeField.clear();
            showSuccess(getTranslation("finance.banksync.notification.connected", session.accounts().size()));
            refresh();
            notifyChanged();
        } catch (RuntimeException ex) {
            showError(ex.getMessage());
        }
    }

    private void onAccountSelected(Account account) {
        if (account == null) {
            unlinkButton.setEnabled(false);
            return;
        }
        unlinkButton.setEnabled(account.isOpenBankingLinked());
        if (account.getExternalAccountId() != null) {
            externalAccountIdField.setValue(account.getExternalAccountId());
        } else {
            externalAccountIdField.clear();
        }
        autoSyncCheckbox.setValue(account.isAutoSyncEnabled());
        if (account.getBankConnectionId() != null) {
            bankSyncService.findConnectionsByHousehold(householdId).stream()
                    .filter(c -> account.getBankConnectionId().equals(c.getId()))
                    .findFirst()
                    .ifPresent(linkConnectionBox::setValue);
        }
    }

    private void onLinkConnectionSelected(BankConnection connection) {
        if (connection == null) {
            externalAccountBox.setItems(List.of());
            autoSyncCheckbox.setEnabled(false);
            return;
        }
        boolean supportsBg = bankSyncService.findProvider(connection.getProviderId())
                .map(BankSyncProvider::supportsBackgroundSync)
                .orElse(false);
        autoSyncCheckbox.setEnabled(supportsBg);
        if (!supportsBg) {
            autoSyncCheckbox.setValue(false);
        }
        if (connection.getStatus() == BankConnectionStatus.ACTIVE) {
            try {
                externalAccountBox.setItems(bankSyncService.fetchExternalAccounts(connection.getId()));
            } catch (RuntimeException ignored) {
                externalAccountBox.setItems(List.of());
            }
        } else {
            externalAccountBox.setItems(List.of());
        }
    }

    private void linkSelectedAccount() {
        Account account = accountBox.getValue();
        BankConnection connection = linkConnectionBox.getValue();
        if (account == null || connection == null) {
            return;
        }
        try {
            bankSyncService.linkAccount(
                    account.getId(),
                    connection.getId(),
                    externalAccountIdField.getValue(),
                    autoSyncCheckbox.getValue());
            showSuccess(getTranslation("finance.banksync.notification.linked"));
            refresh();
            notifyChanged();
        } catch (RuntimeException ex) {
            showError(ex.getMessage());
        }
    }

    private void unlinkSelectedAccount() {
        Account account = accountBox.getValue();
        if (account == null) {
            return;
        }
        try {
            bankSyncService.unlinkAccount(account.getId());
            externalAccountIdField.clear();
            autoSyncCheckbox.setValue(false);
            showSuccess(getTranslation("finance.banksync.notification.unlinked"));
            refresh();
            notifyChanged();
        } catch (RuntimeException ex) {
            showError(ex.getMessage());
        }
    }

    private Span renderStatusBadge(BankConnection connection) {
        BankConnectionStatus status = connection.getStatus();
        Span badge = new Span(getTranslation("finance.banksync.status." + status.name()));
        badge.getElement().getThemeList().add("badge");
        badge.getElement().getThemeList().add("pill");
        switch (status) {
            case ACTIVE -> badge.getElement().getThemeList().add("success");
            case PENDING -> badge.getElement().getThemeList().add("contrast");
            case EXPIRED, ERROR, REVOKED -> badge.getElement().getThemeList().add("error");
        }
        if (connection.getLastError() != null && !connection.getLastError().isBlank()) {
            badge.setTitle(connection.getLastError());
        }
        return badge;
    }

    private Span renderValidityBadge(BankConnection connection) {
        OffsetDateTime now = OffsetDateTime.now();
        if (connection.isConsentExpired(now)) {
            Span expired = new Span(getTranslation("finance.banksync.badge.expired"));
            expired.getElement().getThemeList().add("badge");
            expired.getElement().getThemeList().add("error");
            return expired;
        }
        OptionalLong remaining = connection.getRemainingConsentDays(now);
        if (remaining.isEmpty()) {
            return new Span("–");
        }
        long days = remaining.getAsLong();
        Span badge = new Span(getTranslation("finance.banksync.badge.valid-days", days));
        badge.getElement().getThemeList().add("badge");
        badge.getElement().getThemeList().add(
                connection.isConsentExpiringSoon(now, BankSyncService.DEFAULT_CONSENT_WARNING_DAYS)
                        ? "warning"
                        : "success");
        return badge;
    }

    private HorizontalLayout renderConnectionActions(BankConnection connection) {
        Button syncButton = GridActionButton.create(
                VaadinIcon.REFRESH,
                getTranslation("finance.banksync.button.sync-connection"),
                e -> syncConnectionAccounts(connection));
        syncButton.setEnabled(connection.getStatus() == BankConnectionStatus.ACTIVE);

        Button codeButton = GridActionButton.create(
                VaadinIcon.KEY,
                getTranslation("finance.banksync.button.enter-code"),
                e -> {
                    this.pendingConnectionId = connection.getId();
                    this.redirectLink.setVisible(false);
                    this.pendingAuthPanel.setVisible(true);
                    this.authCodeField.clear();
                });

        Button deleteButton = GridActionButton.createDanger(
                VaadinIcon.TRASH,
                getTranslation("finance.banksync.button.disconnect"),
                e -> {
                    bankSyncService.deleteConnection(connection.getId());
                    showSuccess(getTranslation("finance.banksync.notification.disconnected"));
                    refresh();
                    notifyChanged();
                });

        return new HorizontalLayout(syncButton, codeButton, deleteButton);
    }

    private void syncConnectionAccounts(BankConnection connection) {
        try {
            BankSyncResult result = bankSyncService.syncConnection(connection.getId(), LocalDate.now());
            showSuccess(getTranslation("finance.banksync.notification.synced-batch",
                    result.syncedAccounts(), result.skippedAccounts(), result.failedAccounts()));
            refresh();
            notifyChanged();
        } catch (RuntimeException ex) {
            showError(ex.getMessage());
        }
    }

    private void notifyChanged() {
        if (onChanged != null) {
            onChanged.run();
        }
    }

    private void showSuccess(String message) {
        if (UI.getCurrent() != null) {
            Notification notification = Notification.show(message, NOTIFICATION_DURATION_MS,
                    Notification.Position.BOTTOM_START);
            notification.addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        }
    }

    private void showError(String message) {
        if (UI.getCurrent() != null) {
            Notification notification = Notification.show(
                    getTranslation("finance.banksync.notification.error", message),
                    NOTIFICATION_DURATION_MS,
                    Notification.Position.MIDDLE);
            notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }

    @Override
    public String getTranslation(String key, Object... params) {
        String translation = super.getTranslation(key, params);
        if (translation != null && translation.startsWith("!{") && translation.endsWith("}!")) {
            Locale locale = UI.getCurrent() != null ? UI.getCurrent().getLocale() : Locale.GERMANY;
            return new MoneyPolyI18nProvider().getTranslation(key, locale, params);
        }
        return translation;
    }

    Grid<BankConnection> connectionGridForTest() {
        return connectionGrid;
    }

    Span cryptoWarningForTest() {
        return cryptoWarning;
    }

    private static String formatAccountLabel(Account account) {
        if (account == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (account.getBankName() != null && !account.getBankName().isBlank()) {
            sb.append(account.getBankName().trim()).append(" · ");
        }
        sb.append(account.getName());
        if (account.getAccountHolder() != null && !account.getAccountHolder().isBlank()) {
            sb.append(" (").append(account.getAccountHolder().trim()).append(")");
        }
        return sb.toString();
    }
}
