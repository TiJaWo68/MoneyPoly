package de.in.mp.ui.finance;

import java.net.URL;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vaadin.flow.component.Component;
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
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
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

    private static final Logger LOG = LoggerFactory.getLogger(BankConnectionDialog.class);
    private static final String DIALOG_WIDTH = "960px";
    private static final String DIALOG_HEIGHT = "720px";
    private static final int NOTIFICATION_DURATION_MS = 4000;
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    static final BankInstitution INSTITUTION_SEPARATOR = new BankInstitution("__SEPARATOR__", "", "", "");
    private static final Set<String> LEGAL_SUFFIXES = Set.of(
            "ag", "gmbh", "se", "eg", "kg", "ohg", "mbh", "co", "sarl", "sa", "nv", "plc");

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
    private Set<String> favoriteBankNames = Set.of();
    private String preferredBankName;
    private UUID preferredAccountId;
    private boolean callbackUrlEditedByUser;
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
        openForHousehold(householdId, null, null, onChanged);
    }

    void openForHousehold(UUID householdId, String preferredBankName, UUID preferredAccountId, Runnable onChanged) {
        initForHousehold(householdId, preferredBankName, preferredAccountId, onChanged);
        resolveDefaultCallbackUrl();
        open();
    }

    void initForHousehold(UUID householdId, Runnable onChanged) {
        initForHousehold(householdId, null, null, onChanged);
    }

    void initForHousehold(UUID householdId, String preferredBankName, UUID preferredAccountId, Runnable onChanged) {
        this.householdId = householdId;
        this.preferredBankName = (preferredBankName == null || preferredBankName.isBlank())
                ? null
                : preferredBankName.trim();
        this.preferredAccountId = preferredAccountId;
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
        institutionBox.setItemLabelGenerator(inst -> isSeparator(inst) ? "" : inst.name());
        institutionBox.setRenderer(new ComponentRenderer<>(this::renderInstitutionItem));
        institutionBox.addValueChangeListener(e -> {
            if (isSeparator(e.getValue())) {
                institutionBox.setValue(e.getOldValue());
            }
        });

        callbackUrlField.setLabel(getTranslation("finance.banksync.field.callback-url"));
        callbackUrlField.setTooltipText(getTranslation("finance.banksync.dialog.callback-url.tooltip"));
        callbackUrlField.setValue("http://localhost:8080/finance");
        callbackUrlField.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                callbackUrlEditedByUser = true;
            }
        });
        resolveDefaultCallbackUrl();

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

        List<Account> allAccounts = householdId == null
                ? accountService.findAll()
                : accountService.findAllByHousehold(householdId);
        List<Account> activeLeafAccounts = filterEffectivelyActiveLeafAccounts(allAccounts);
        this.favoriteBankNames = extractHouseholdBankNames(allAccounts);

        if (providerBox.getValue() == null && !bankSyncService.getProviders().isEmpty()) {
            providerBox.setValue(bankSyncService.getProviders().get(0));
        } else {
            loadInstitutions(providerBox.getValue());
        }

        List<BankConnection> connections = bankSyncService.findConnectionsByHousehold(householdId);
        connectionGrid.setItems(connections);
        linkConnectionBox.setItems(connections);

        accountBox.setItems(activeLeafAccounts);
        if (preferredAccountId != null) {
            activeLeafAccounts.stream()
                    .filter(a -> preferredAccountId.equals(a.getId()))
                    .findFirst()
                    .ifPresent(accountBox::setValue);
        }
    }

    private void loadInstitutions(BankSyncProvider provider) {
        if (provider == null) {
            institutionBox.setItems(List.of());
            return;
        }
        try {
            List<BankInstitution> ordered = orderInstitutionsWithFavorites(
                    provider.listInstitutions("DE"), favoriteBankNames);
            ComboBox.ItemFilter<BankInstitution> filter = (item, filterText) -> {
                if (isSeparator(item)) {
                    return filterText == null || filterText.isBlank();
                }
                if (filterText == null || filterText.isBlank()) {
                    return true;
                }
                String needle = filterText.trim().toLowerCase(Locale.ROOT);
                return (item.name() != null && item.name().toLowerCase(Locale.ROOT).contains(needle))
                        || (item.bic() != null && item.bic().toLowerCase(Locale.ROOT).contains(needle));
            };
            institutionBox.setItems(filter, ordered);
            BankInstitution preselected = findPreferredInstitution(ordered, preferredBankName);
            if (preselected != null) {
                institutionBox.setValue(preselected);
            } else {
                ordered.stream()
                        .filter(inst -> !isSeparator(inst))
                        .findFirst()
                        .ifPresent(institutionBox::setValue);
            }
        } catch (RuntimeException ex) {
            institutionBox.setItems(List.of());
            showError(ex.getMessage());
        }
    }

    private Component renderInstitutionItem(BankInstitution institution) {
        if (isSeparator(institution)) {
            Div divider = new Div();
            divider.getStyle()
                    .set("width", "100%")
                    .set("border-top", "1px solid var(--lumo-contrast-20pct)")
                    .set("margin", "2px 0");
            divider.getElement().executeJs(
                    "const item = this.closest('vaadin-combo-box-item');"
                            + "if (item) { item.style.pointerEvents = 'none';"
                            + "item.style.minHeight = '9px'; item.style.padding = '2px 8px'; }");
            return divider;
        }
        boolean favorite = matchesAccountBank(institution.name(), favoriteBankNames);
        if (!favorite) {
            return new Span(institution.name());
        }
        Icon star = VaadinIcon.STAR.create();
        star.setSize("14px");
        star.getStyle().set("color", "#f59e0b").set("flex-shrink", "0");
        Span label = new Span(institution.name());
        label.addClassName(LumoUtility.FontWeight.SEMIBOLD);
        HorizontalLayout row = new HorizontalLayout(star, label);
        row.setSpacing(false);
        row.getStyle().set("gap", "6px");
        row.setDefaultVerticalComponentAlignment(Alignment.CENTER);
        return row;
    }

    static List<BankInstitution> orderInstitutionsWithFavorites(
            List<BankInstitution> institutions, Set<String> householdBankNames) {
        if (institutions == null || institutions.isEmpty()) {
            return List.of();
        }
        Comparator<BankInstitution> alphabetical = Comparator
                .comparing(BankInstitution::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(BankInstitution::name);

        List<BankInstitution> favorites = new ArrayList<>();
        List<BankInstitution> others = new ArrayList<>();
        for (BankInstitution inst : institutions) {
            if (inst == null || isSeparator(inst) || inst.name() == null || inst.name().isBlank()) {
                continue;
            }
            if (matchesAccountBank(inst.name(), householdBankNames)) {
                favorites.add(inst);
            } else {
                others.add(inst);
            }
        }
        favorites.sort(alphabetical);
        others.sort(alphabetical);

        List<BankInstitution> result = new ArrayList<>(favorites.size() + others.size() + 1);
        result.addAll(favorites);
        if (!favorites.isEmpty() && !others.isEmpty()) {
            result.add(INSTITUTION_SEPARATOR);
        }
        result.addAll(others);
        return result;
    }

    static Set<String> extractHouseholdBankNames(List<Account> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            return Set.of();
        }
        Map<UUID, Account> byId = indexAccountsById(accounts);
        Set<String> names = new LinkedHashSet<>();
        for (Account account : filterEffectivelyActiveLeafAccounts(accounts, byId)) {
            if (account.getBankName() != null && !account.getBankName().isBlank()) {
                names.add(account.getBankName().trim());
                continue;
            }
            String institutionName = findAncestorInstitutionName(account, byId);
            if (institutionName != null) {
                names.add(institutionName);
            }
        }
        return names;
    }

    static List<Account> filterEffectivelyActiveLeafAccounts(List<Account> accounts) {
        return filterEffectivelyActiveLeafAccounts(accounts, indexAccountsById(accounts));
    }

    private static List<Account> filterEffectivelyActiveLeafAccounts(
            List<Account> accounts, Map<UUID, Account> byId) {
        if (accounts == null || accounts.isEmpty()) {
            return List.of();
        }
        List<Account> result = new ArrayList<>();
        for (Account account : accounts) {
            if (account != null
                    && account.getAccountType() != AccountType.INSTITUTION
                    && isEffectivelyActive(account, byId)) {
                result.add(account);
            }
        }
        return result;
    }

    private static Map<UUID, Account> indexAccountsById(List<Account> accounts) {
        Map<UUID, Account> byId = new HashMap<>();
        if (accounts != null) {
            for (Account account : accounts) {
                if (account != null && account.getId() != null) {
                    byId.put(account.getId(), account);
                }
            }
        }
        return byId;
    }

    private static boolean isEffectivelyActive(Account account, Map<UUID, Account> byId) {
        Set<UUID> visited = new HashSet<>();
        Account current = account;
        while (current != null) {
            if (!current.isActive()) {
                return false;
            }
            UUID parentId = current.getParentId();
            if (parentId == null || !visited.add(parentId)) {
                return true;
            }
            current = byId.get(parentId);
        }
        return true;
    }

    private static String findAncestorInstitutionName(Account account, Map<UUID, Account> byId) {
        Set<UUID> visited = new HashSet<>();
        UUID parentId = account.getParentId();
        while (parentId != null && visited.add(parentId)) {
            Account ancestor = byId.get(parentId);
            if (ancestor == null) {
                return null;
            }
            if (ancestor.getAccountType() == AccountType.INSTITUTION) {
                return ancestor.getName() == null || ancestor.getName().isBlank()
                        ? null
                        : ancestor.getName().trim();
            }
            parentId = ancestor.getParentId();
        }
        return null;
    }

    private void resolveDefaultCallbackUrl() {
        UI ui = UI.getCurrent();
        if (ui == null || callbackUrlEditedByUser) {
            return;
        }
        ui.getPage().fetchCurrentURL(currentUrl -> {
            if (!callbackUrlEditedByUser) {
                callbackUrlField.setValue(buildFinanceCallbackUrl(currentUrl));
            }
        });
    }

    static String buildFinanceCallbackUrl(URL currentUrl) {
        if (currentUrl == null || currentUrl.getAuthority() == null || currentUrl.getAuthority().isBlank()) {
            return "http://localhost:8080/finance";
        }
        return currentUrl.getProtocol() + "://" + currentUrl.getAuthority() + "/finance";
    }

    static boolean matchesAccountBank(String institutionName, Set<String> accountBankNames) {
        if (institutionName == null || institutionName.isBlank()
                || accountBankNames == null || accountBankNames.isEmpty()) {
            return false;
        }
        String instNorm = normalizeBankText(institutionName);
        String instCore = stripLegalSuffixes(instNorm);
        String paddedInstCore = " " + instCore + " ";

        for (String accountBank : accountBankNames) {
            if (accountBank == null || accountBank.isBlank()) {
                continue;
            }
            String accNorm = normalizeBankText(accountBank);
            String accCore = stripLegalSuffixes(accNorm);
            if (accCore.isEmpty()) {
                continue;
            }
            if (instNorm.equals(accNorm) || instCore.equals(accCore)) {
                return true;
            }
            if (accCore.length() >= 3 && !"bank".equals(accCore)
                    && paddedInstCore.contains(" " + accCore + " ")) {
                return true;
            }
            if (instCore.length() >= 3 && !"bank".equals(instCore)
                    && (" " + accCore + " ").contains(" " + instCore + " ")) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeBankText(String raw) {
        return raw.toLowerCase(Locale.ROOT)
                .replaceAll("[-/_.(),]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String stripLegalSuffixes(String normalized) {
        String[] tokens = normalized.split(" ");
        List<String> kept = new ArrayList<>(tokens.length);
        for (String token : tokens) {
            if (!token.isEmpty() && !LEGAL_SUFFIXES.contains(token)) {
                kept.add(token);
            }
        }
        return kept.isEmpty() ? normalized : String.join(" ", kept);
    }

    static boolean isSeparator(BankInstitution institution) {
        return institution != null && INSTITUTION_SEPARATOR.id().equals(institution.id());
    }

    private static BankInstitution findPreferredInstitution(
            List<BankInstitution> ordered, String preferredBankName) {
        if (ordered == null || ordered.isEmpty() || preferredBankName == null || preferredBankName.isBlank()) {
            return null;
        }
        Set<String> target = Set.of(preferredBankName);
        return ordered.stream()
                .filter(inst -> !isSeparator(inst) && matchesAccountBank(inst.name(), target))
                .findFirst()
                .orElse(null);
    }

    private void startAuthorization() {
        BankSyncProvider provider = providerBox.getValue();
        BankInstitution institution = institutionBox.getValue();
        if (provider == null || institution == null || isSeparator(institution)) {
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
            LOG.warn("Failed to start Open-Banking authorization for household {} and institution {}",
                    householdId, institution.name(), ex);
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
            LOG.warn("Failed to complete Open-Banking authorization for connection {}",
                    pendingConnectionId, ex);
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

    ComboBox<BankInstitution> institutionBoxForTest() {
        return institutionBox;
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
