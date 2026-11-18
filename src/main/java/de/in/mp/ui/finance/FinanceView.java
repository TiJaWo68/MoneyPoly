package de.in.mp.ui.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.grid.HeaderRow;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.treegrid.TreeGrid;
import com.vaadin.flow.data.provider.hierarchy.TreeData;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.theme.lumo.LumoUtility;

import de.in.mp.admin.AppSettingService;
import de.in.mp.finance.Account;
import de.in.mp.finance.AccountService;
import de.in.mp.finance.AccountSnapshot;
import de.in.mp.finance.AccountSnapshotService;
import de.in.mp.finance.AccountType;
import de.in.mp.finance.BalanceDateRow;
import de.in.mp.finance.BankLookupService;
import de.in.mp.finance.DepotValuation;
import de.in.mp.finance.Household;
import de.in.mp.finance.HouseholdService;
import de.in.mp.finance.PortfolioPositionImportService;
import de.in.mp.finance.PortfolioPositionService;
import de.in.mp.finance.banksync.BankConnection;
import de.in.mp.finance.banksync.BankSyncResult;
import de.in.mp.finance.banksync.BankSyncService;
import de.in.mp.finance.exitplan.ExitPlanCalculator;
import de.in.mp.i18n.DateFormatter;
import de.in.mp.i18n.MoneyFormatter;
import de.in.mp.i18n.MoneyPolyI18nProvider;
import de.in.mp.importer.StatementImportService;
import de.in.mp.ui.ActionMenu;
import de.in.mp.ui.GridActionButton;
import de.in.mp.ui.ResponsiveStyles;
import de.in.mp.ui.layout.MainLayout;
import jakarta.annotation.security.PermitAll;

/**
 * Finance overview with two tabs:
 * <ul>
 * <li><b>Balances</b>: balance history matrix (one row per reference date,
 * one column per account) with quick entry and PDF statement import,</li>
 * <li><b>Accounts</b>: hierarchical account tree with master data editing.</li>
 * </ul>
 */
@Route(value = "finance", layout = MainLayout.class)
@PermitAll
public class FinanceView extends VerticalLayout implements HasDynamicTitle, BeforeEnterObserver {

    public static final String ACCOUNT_QUERY_PARAM = "account";

    private final AccountService accountService;
    private static final String GRID_MIN_HEIGHT = "380px";
    private static final String SIGNATURE_DELIMITER = "|";

    private final AccountSnapshotService snapshotService;
    private final AppSettingService appSettingService;
    private final HouseholdService householdService;
    private final PortfolioPositionService positionService;
    private final BankSyncService bankSyncService;
    private final AccountEditorDialog editorDialog;
    private final BalanceEntryDialog balanceDialog;
    private final StatementImportDialog statementDialog;
    private final PortfolioPositionsDialog positionsDialog;
    private final BankConnectionDialog connectionDialog;
    private final ComboBox<Household> householdSelector = new ComboBox<>();
    private UUID selectedHouseholdId;

    private final Span netWorthValue = new Span();
    private final Span activeAccountsValue = new Span();
    private final Span latestDateValue = new Span();

    private final Tabs tabBar = new Tabs();
    private final Tab chartTab = new Tab();
    private final Tab balancesTab = new Tab();
    private final Tab accountsTab = new Tab();
    private final Tab exitPlanTab = new Tab();
    private final VerticalLayout tabContent = new VerticalLayout();

    private final BalanceChartPanel chartPanel = new BalanceChartPanel();
    private final ExitPlanPanel exitPlanPanel = new ExitPlanPanel(new ExitPlanCalculator());
    private Grid<BalanceDateRow> balanceGrid = createBalanceGrid();
    private final TreeGrid<Account> treeGrid = new TreeGrid<>();
    private final ActionMenu actionMenu;
    private final HorizontalLayout kpiRow;

    private boolean showInactiveAccounts;
    private boolean kpisVisible = true;
    private List<String> configuredBalanceSignature;
    private List<Account> displayedBalanceColumns = List.of();
    private Map<UUID, BigDecimal> balancesByAccount = Map.of();
    private Map<UUID, BigDecimal> institutionTotals = Map.of();
    private Map<UUID, BankConnection> connectionsById = Map.of();
    private List<Account> currentAccounts = List.of();

    public FinanceView(AccountService accountService, AccountSnapshotService snapshotService,
            BankLookupService bankLookupService, StatementImportService statementImportService,
            AppSettingService appSettingService) {
        this(accountService, snapshotService, bankLookupService, statementImportService, appSettingService,
                null, null, null, null);
    }

    public FinanceView(AccountService accountService, AccountSnapshotService snapshotService,
            BankLookupService bankLookupService, StatementImportService statementImportService,
            AppSettingService appSettingService,
            HouseholdService householdService) {
        this(accountService, snapshotService, bankLookupService, statementImportService, appSettingService,
                householdService, null, null, null);
    }

    public FinanceView(AccountService accountService, AccountSnapshotService snapshotService,
            BankLookupService bankLookupService, StatementImportService statementImportService,
            AppSettingService appSettingService,
            HouseholdService householdService,
            PortfolioPositionService positionService) {
        this(accountService, snapshotService, bankLookupService, statementImportService, appSettingService,
                householdService, positionService, null, null);
    }

    public FinanceView(AccountService accountService, AccountSnapshotService snapshotService,
            BankLookupService bankLookupService, StatementImportService statementImportService,
            AppSettingService appSettingService,
            HouseholdService householdService,
            PortfolioPositionService positionService,
            PortfolioPositionImportService positionImportService) {
        this(accountService, snapshotService, bankLookupService, statementImportService, appSettingService,
                householdService, positionService, positionImportService, null);
    }

    @Autowired
    public FinanceView(AccountService accountService, AccountSnapshotService snapshotService,
            BankLookupService bankLookupService, StatementImportService statementImportService,
            AppSettingService appSettingService,
            @Autowired(required = false) HouseholdService householdService,
            @Autowired(required = false) PortfolioPositionService positionService,
            @Autowired(required = false) PortfolioPositionImportService positionImportService,
            @Autowired(required = false) BankSyncService bankSyncService) {
        this.accountService = accountService;
        this.snapshotService = snapshotService;
        this.appSettingService = appSettingService;
        this.householdService = householdService;
        this.positionService = positionService;
        this.bankSyncService = bankSyncService;
        this.connectionDialog = bankSyncService != null
                ? new BankConnectionDialog(bankSyncService, accountService)
                : null;
        this.editorDialog = new AccountEditorDialog(
                accountService,
                bankLookupService,
                bankSyncService,
                this.connectionDialog != null ? this::openConnectionsFromEditor : null);
        this.balanceDialog = new BalanceEntryDialog(accountService, snapshotService, positionService);
        this.statementDialog = new StatementImportDialog(statementImportService, accountService);
        this.positionsDialog = positionService != null
                ? new PortfolioPositionsDialog(positionService, snapshotService, positionImportService, canModify())
                : null;
        this.actionMenu = new ActionMenu(getTranslation("ui.action-menu.tooltip"));
        this.kpiRow = createKpiRow();
        this.kpiRow.addClassNames(LumoUtility.Margin.Bottom.XSMALL, ResponsiveStyles.HIDE_ON_SHORT_SCREEN);
        this.kpiRow.setVisible(kpisVisible);

        setSizeFull();
        setPadding(false);
        addClassNames(LumoUtility.Padding.Horizontal.MEDIUM, LumoUtility.Padding.Vertical.XSMALL);
        setSpacing(false);
        getStyle().set("overflow-y", "auto");

        chartTab.setLabel(getTranslation("finance.tab.chart"));
        balancesTab.setLabel(getTranslation("finance.tab.balances"));
        accountsTab.setLabel(getTranslation("finance.tab.accounts"));
        exitPlanTab.setLabel(getTranslation("finance.tab.exit-plan"));
        exitPlanTab.setVisible(isExitPlanEnabledForSelectedHousehold());
        tabBar.add(chartTab, balancesTab, accountsTab, exitPlanTab);
        tabBar.setWidthFull();
        tabBar.setVisible(false);
        tabBar.addSelectedChangeListener(event -> renderSelectedTab());

        tabContent.setSizeFull();
        tabContent.setPadding(false);
        tabContent.getStyle().set("min-height", "400px");

        configureTreeGrid();
        treeGrid.setSizeFull();
        treeGrid.getStyle().set("min-height", GRID_MIN_HEIGHT);

        configureHouseholdSelector();
        HorizontalLayout titleHeader = actionMenu.createTitleHeader(getTranslation("finance.view.title"));
        add(titleHeader, kpiRow, tabBar, tabContent);
        expand(tabContent);
        refresh();
        renderSelectedTab();
    }

    @Override
    public String getPageTitle() {
        return getTranslation("finance.view.page-title");
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        Map<String, List<String>> params = event.getLocation().getQueryParameters().getParameters();
        List<String> householdParam = params.get("household");
        if (householdParam != null && !householdParam.isEmpty() && householdService != null) {
            try {
                UUID requestedId = UUID.fromString(householdParam.get(0));
                boolean allowed = householdService.getAccessibleHouseholdsForCurrentUser().stream()
                        .anyMatch(h -> h.getId().equals(requestedId));
                if (allowed && !Objects.equals(selectedHouseholdId, requestedId)) {
                    selectedHouseholdId = requestedId;
                    refresh();
                }
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed query parameter.
            }
        }

        updateExitPlanTabVisibility();
        List<String> tabParam = params.get("tab");
        if (tabParam != null && !tabParam.isEmpty()) {
            switch (tabParam.get(0)) {
                case "chart" -> tabBar.setSelectedTab(chartTab);
                case "balances" -> tabBar.setSelectedTab(balancesTab);
                case "accounts" -> tabBar.setSelectedTab(accountsTab);
                case "exit-plan" -> {
                    if (exitPlanTab.isVisible()) {
                        tabBar.setSelectedTab(exitPlanTab);
                    }
                }
                default -> { }
            }
            renderSelectedTab();
        }

        List<String> values = params.get(ACCOUNT_QUERY_PARAM);
        if (values != null && !values.isEmpty()) {
            try {
                focusAccount(UUID.fromString(values.get(0)));
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed query parameter.
            }
        }

        handleOpenBankingCallback(params);
    }

    private void handleOpenBankingCallback(Map<String, List<String>> params) {
        if (bankSyncService == null || !canModify()) {
            return;
        }
        List<String> connParam = params.get("bank_connection_id");
        List<String> codeParam = params.containsKey("code") ? params.get("code") : params.get("ticket");
        if (connParam == null || connParam.isEmpty() || codeParam == null || codeParam.isEmpty()) {
            return;
        }
        try {
            UUID connectionId = UUID.fromString(connParam.get(0));
            var authorized = bankSyncService.completeConnection(connectionId, codeParam.get(0));
            if (UI.getCurrent() != null) {
                Notification notification = Notification.show(
                        getTranslation("finance.banksync.notification.connected", authorized.accounts().size()),
                        4000,
                        Notification.Position.BOTTOM_START);
                notification.addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            }
            refresh();
        } catch (RuntimeException ex) {
            if (UI.getCurrent() != null) {
                Notification notification = Notification.show(
                        getTranslation("finance.banksync.notification.error", ex.getMessage()),
                        4000,
                        Notification.Position.MIDDLE);
                notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
            }
        }
    }

    void focusAccount(UUID accountId) {
        if (accountId == null) {
            return;
        }
        tabBar.setSelectedTab(accountsTab);

        Account target = findCurrentAccount(accountId);
        if (target == null) {
            return;
        }

        if (!treeGrid.getTreeData().contains(target) && !showInactiveAccounts) {
            setShowInactiveAccounts(true);
            target = findCurrentAccount(accountId);
        }

        if (target == null || !treeGrid.getTreeData().contains(target)) {
            return;
        }

        expandAncestors(target);
        treeGrid.select(target);
        int[] path = hierarchyPathForTest(target);
        if (path.length > 0) {
            treeGrid.scrollToIndex(path);
        }
    }

    private Account findCurrentAccount(UUID accountId) {
        return currentAccounts.stream()
                .filter(account -> accountId.equals(account.getId()))
                .findFirst()
                .orElse(null);
    }

    private void expandAncestors(Account target) {
        TreeData<Account> treeData = treeGrid.getTreeData();
        Deque<Account> ancestors = new ArrayDeque<>();
        Account parent = treeData.getParent(target);
        while (parent != null) {
            ancestors.push(parent);
            parent = treeData.getParent(parent);
        }
        while (!ancestors.isEmpty()) {
            treeGrid.expand(ancestors.pop());
        }
    }

    private void rebuildActionMenu() {
        actionMenu.clear();
        Tab selected = tabBar.getSelectedTab();

        if (canModify()) {
            actionMenu.addAction(
                    getTranslation("finance.view.button.record-balances"),
                    VaadinIcon.PLUS,
                    () -> balanceDialog.openForCreate(this::refresh));
            actionMenu.addAction(
                    getTranslation("finance.view.button.import-statement"),
                    VaadinIcon.FILE_TEXT_O,
                    () -> statementDialog.openForImport(this::refresh));
            if (bankSyncService != null) {
                actionMenu.addAction(
                        getTranslation("finance.banksync.action.sync-all"),
                        VaadinIcon.REFRESH,
                        this::syncAllLinkedAccounts);
            }

            if (selected == accountsTab) {
                actionMenu.addSeparator();
                actionMenu.addAction(
                        getTranslation("finance.view.button.new-account"),
                        VaadinIcon.PLUS_CIRCLE_O,
                        () -> {
                            editorDialog.openForCreate(this::refresh);
                            editorDialog.refreshBankConnections(resolveActiveHouseholdId());
                        });
                if (bankSyncService != null && connectionDialog != null) {
                    actionMenu.addAction(
                            getTranslation("finance.banksync.action.manage-connections"),
                            VaadinIcon.CONNECT,
                            () -> connectionDialog.openForHousehold(resolveActiveHouseholdId(), this::refresh));
                }
                actionMenu.addAction(
                        getTranslation("finance.view.button.threshold"),
                        VaadinIcon.SLIDERS,
                        this::openThresholdDialog);
            }

            if (householdService != null && resolveActiveHouseholdId() != null) {
                actionMenu.addSeparator();
                actionMenu.addToggle(
                        getTranslation("finance.action.toggle-exit-plan"),
                        isExitPlanEnabledForSelectedHousehold(),
                        this::setExitPlanEnabled);
            }

            actionMenu.addSeparator();
        }
        if (selected == balancesTab || selected == accountsTab) {
            actionMenu.addToggle(
                    getTranslation("finance.balance.show-inactive"),
                    showInactiveAccounts,
                    this::setShowInactiveAccounts);
        }
        if (selected != exitPlanTab) {
            actionMenu.addToggle(
                    getTranslation("ui.action-menu.show-kpis"),
                    kpisVisible,
                    this::setKpisVisible);
        }
    }

    private void setShowInactiveAccounts(boolean value) {
        if (this.showInactiveAccounts != value) {
            this.showInactiveAccounts = value;
            refresh();
            rebuildActionMenu();
        }
    }

    private void setKpisVisible(boolean visible) {
        this.kpisVisible = visible;
        updateKpiRowVisibility();
    }

    private void updateKpiRowVisibility() {
        kpiRow.setVisible(kpisVisible && tabBar.getSelectedTab() != exitPlanTab);
    }

    private HorizontalLayout createKpiRow() {
        HorizontalLayout row = new HorizontalLayout(
                createKpiCard(getTranslation("finance.view.kpi.net-worth"), netWorthValue),
                createKpiCard(getTranslation("finance.view.kpi.active-accounts"), activeAccountsValue),
                createKpiCard(getTranslation("finance.view.kpi.latest-date"), latestDateValue));
        row.setWidthFull();
        row.setSpacing(true);
        row.getStyle().set("flex-wrap", "wrap");
        return row;
    }

    private VerticalLayout createKpiCard(String label, Span value) {
        value.addClassNames(LumoUtility.FontSize.LARGE, LumoUtility.FontWeight.BOLD);

        Span labelSpan = new Span(label);
        labelSpan.addClassNames(LumoUtility.TextColor.SECONDARY, LumoUtility.FontSize.XSMALL);

        VerticalLayout card = new VerticalLayout(labelSpan, value);
        card.setPadding(false);
        card.setSpacing(false);
        card.getStyle().set("flex", "1 1 150px");
        card.setMinWidth("150px");
        card.addClassNames(
                LumoUtility.Padding.Vertical.XSMALL,
                LumoUtility.Padding.Horizontal.MEDIUM,
                LumoUtility.Background.CONTRAST_5,
                LumoUtility.BorderRadius.MEDIUM,
                LumoUtility.BoxShadow.SMALL);
        return card;
    }

    private void renderSelectedTab() {
        tabContent.removeAll();
        if (tabBar.getSelectedTab() == chartTab) {
            tabContent.add(chartPanel);
            tabContent.expand(chartPanel);
        } else if (tabBar.getSelectedTab() == accountsTab) {
            tabContent.add(treeGrid);
            tabContent.expand(treeGrid);
        } else if (tabBar.getSelectedTab() == exitPlanTab && exitPlanTab.isVisible()) {
            tabContent.add(exitPlanPanel);
            tabContent.expand(exitPlanPanel);
        } else {
            tabContent.add(balanceGrid);
            tabContent.expand(balanceGrid);
        }
        updateKpiRowVisibility();
        rebuildActionMenu();
    }

    // ---------------------------------------------------------------- balances

    private List<Account> visibleBalanceAccounts(List<Account> accounts, List<BalanceDateRow> rows) {
        Map<UUID, Account> byId = AccountLabels.byId(accounts);
        return AccountLabels.matrixOrder(accounts, byId).stream()
                .filter(account -> showInactiveAccounts || account.isActive())
                .filter(account -> account.isActive() || hasSnapshots(account, rows))
                .toList();
    }

    private static Grid<BalanceDateRow> createBalanceGrid() {
        Grid<BalanceDateRow> grid = new Grid<>();
        grid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_COLUMN_BORDERS,
                GridVariant.LUMO_COMPACT);
        grid.setSizeFull();
        grid.getStyle().set("min-height", GRID_MIN_HEIGHT);
        return grid;
    }

    private void rebuildBalanceColumns(List<Account> columns, Map<UUID, Account> byId) {
        balanceGrid.addColumn(row -> DateFormatter.format(row.date(), locale()))
                .setHeader(getTranslation("finance.balance.field.date"))
                .setFrozen(true)
                .setAutoWidth(true);

        balanceGrid.addColumn(row -> formatMoney(sumOf(row, displayedBalanceColumns)))
                .setHeader(getTranslation("finance.balance.column.total"))
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);

        List<AccountColumnEntry> accountColumns = new java.util.ArrayList<>();
        for (Account account : columns) {
            UUID accountId = account.getId();
            Grid.Column<BalanceDateRow> column = balanceGrid
                    .addColumn(row -> formatSnapshot(row.snapshotFor(accountId)))
                    .setHeader(createAccountColumnHeader(account))
                    .setTooltipGenerator(row -> tooltip(row.snapshotFor(accountId)))
                    .setTextAlign(ColumnTextAlign.END)
                    .setAutoWidth(true);

            String group = AccountLabels.institutionName(account, byId);
            accountColumns.add(new AccountColumnEntry(group, column));
        }

        balanceGrid.addColumn(new ComponentRenderer<>(this::renderRowActions))
                .setHeader(getTranslation("finance.grid.column.actions"))
                .setAutoWidth(true);

        HeaderRow groupHeaderRow = balanceGrid.prependHeaderRow();
        applyGroupedHeaders(groupHeaderRow, accountColumns);
    }

    private static List<String> balanceColumnSignature(List<Account> columns, Map<UUID, Account> byId) {
        return columns.stream()
                .map(account -> String.join(SIGNATURE_DELIMITER,
                        Objects.toString(account.getId(), ""),
                        Objects.toString(account.getAccountType(), ""),
                        Objects.toString(account.getName(), ""),
                        Objects.toString(account.getAccountHolder(), ""),
                        Objects.toString(account.getAccountNumber(), ""),
                        Objects.toString(AccountLabels.institutionName(account, byId), "")))
                .toList();
    }

    Div createAccountColumnHeader(Account account) {
        String typeLabel = account.getAccountType() == null
                ? account.getName()
                : getTranslation("finance.account.type." + account.getAccountType().name());
        Span typeSpan = new Span(typeLabel);

        String holder = account.getAccountHolder() == null ? "" : account.getAccountHolder().trim();
        Span holderSpan = new Span(holder);
        holderSpan.addClassNames(LumoUtility.FontSize.XSMALL, LumoUtility.TextColor.SECONDARY);

        Div header = new Div(typeSpan, holderSpan);
        header.getStyle()
                .set("display", "flex")
                .set("flex-direction", "column")
                .set("line-height", "1.25");

        String iban = account.getAccountNumber();
        if (iban != null && !iban.isBlank()) {
            header.getElement().setAttribute("title", iban.trim());
        }
        return header;
    }

    private void applyGroupedHeaders(HeaderRow topRow, List<AccountColumnEntry> accountColumns) {
        int i = 0;
        while (i < accountColumns.size()) {
            String currentGroup = accountColumns.get(i).institutionGroup();
            List<Grid.Column<BalanceDateRow>> run = new java.util.ArrayList<>();
            while (i < accountColumns.size()
                    && java.util.Objects.equals(accountColumns.get(i).institutionGroup(), currentGroup)) {
                run.add(accountColumns.get(i).column());
                i++;
            }
            if (currentGroup != null && !run.isEmpty()) {
                if (run.size() == 1) {
                    topRow.getCell(run.get(0)).setText(currentGroup);
                } else {
                    topRow.join(run.toArray(new Grid.Column[0])).setText(currentGroup);
                }
            }
        }
    }

    private record AccountColumnEntry(String institutionGroup, Grid.Column<BalanceDateRow> column) {
    }

    private static boolean hasSnapshots(Account account, List<BalanceDateRow> rows) {
        return rows.stream().anyMatch(row -> row.snapshotFor(account.getId()) != null);
    }

    private static BigDecimal sumOf(BalanceDateRow row, List<Account> columns) {
        BigDecimal total = BigDecimal.ZERO;
        for (Account account : columns) {
            AccountSnapshot snapshot = row.snapshotFor(account.getId());
            if (snapshot == null) {
                continue;
            }
            total = account.getAccountType() == AccountType.LOAN
                    ? total.subtract(snapshot.getBalance())
                    : total.add(snapshot.getBalance());
        }
        return total;
    }

    private String formatSnapshot(AccountSnapshot snapshot) {
        return snapshot == null ? "" : formatMoney(snapshot.getBalance());
    }

    private String tooltip(AccountSnapshot snapshot) {
        if (snapshot == null || (snapshot.getInflow() == null && snapshot.getOutflow() == null
                && snapshot.getNotes() == null && snapshot.getInvestedCapital() == null)) {
            return "";
        }
        List<String> parts = new java.util.ArrayList<>();
        if (snapshot.getInflow() != null || snapshot.getOutflow() != null
                || (snapshot.getNotes() != null && !snapshot.getNotes().isBlank())) {
            parts.add(getTranslation("finance.balance.tooltip",
                    formatMoney(snapshot.getInflow()),
                    formatMoney(snapshot.getOutflow()),
                    snapshot.getNotes() == null ? "" : snapshot.getNotes()));
        }
        de.in.mp.finance.InvestmentPerformance.of(snapshot.getBalance(), snapshot.getInvestedCapital())
                .ifPresent(perf -> parts.add(getTranslation("finance.balance.tooltip.invested",
                        formatMoney(snapshot.getInvestedCapital()),
                        perf.format(locale()))));
        return String.join(" | ", parts);
    }

    private HorizontalLayout renderRowActions(BalanceDateRow row) {
        if (!canModify()) {
            return new HorizontalLayout();
        }
        Button editButton = GridActionButton.create(VaadinIcon.EDIT,
                getTranslation("finance.balance.action.edit-row"),
                event -> balanceDialog.openForEdit(row, this::refresh));
        Button deleteButton = GridActionButton.createDanger(VaadinIcon.TRASH,
                getTranslation("finance.balance.action.delete-row"),
                event -> {
                    snapshotService.deleteSnapshotsForDate(selectedHouseholdId, row.date());
                    Notification.show(getTranslation("finance.balance.notification.deleted"), 3000,
                            Notification.Position.BOTTOM_START);
                    refresh();
                });
        return new HorizontalLayout(editButton, deleteButton);
    }

    // ---------------------------------------------------------------- accounts

    private void configureTreeGrid() {
        treeGrid.addComponentHierarchyColumn(this::renderHierarchyCell)
                .setHeader(getTranslation("finance.grid.column.type"))
                .setAutoWidth(true);

        treeGrid.addColumn(account -> account.getAccountHolder() != null ? account.getAccountHolder() : "")
                .setHeader(getTranslation("finance.grid.column.holder"))
                .setAutoWidth(true);

        treeGrid.addColumn(account -> account.getAccountNumber() != null ? account.getAccountNumber() : "")
                .setHeader(getTranslation("finance.grid.column.account-number"))
                .setAutoWidth(true);

        treeGrid.addColumn(new ComponentRenderer<>(this::renderInterestRate))
                .setHeader(getTranslation("finance.grid.column.interest-rate"))
                .setTooltipGenerator(this::interestTooltip)
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);

        treeGrid.addColumn(new ComponentRenderer<>(this::renderBalance))
                .setHeader(getTranslation("finance.grid.column.balance"))
                .setAutoWidth(true);

        treeGrid.addColumn(new ComponentRenderer<>(this::renderStatus))
                .setHeader(getTranslation("finance.grid.column.status"))
                .setAutoWidth(true);

        treeGrid.addColumn(new ComponentRenderer<>(this::renderAccountActions))
                .setHeader(getTranslation("finance.grid.column.actions"))
                .setAutoWidth(true);

        treeGrid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_COLUMN_BORDERS);
    }

    Span renderHierarchyCell(Account account) {
        if (account.getAccountType() == AccountType.INSTITUTION) {
            Span institutionLabel = new Span(account.getName());
            institutionLabel.addClassName(LumoUtility.FontWeight.SEMIBOLD);
            return institutionLabel;
        }
        return renderTypeBadge(account);
    }

    private Span renderTypeBadge(Account account) {
        AccountType type = account.getAccountType();
        Span badge = new Span(getTranslation("finance.account.type." + type.name()));
        badge.getElement().getThemeList().add("badge");
        badge.getElement().getThemeList().add("pill");

        switch (type) {
            case SAVINGS -> badge.getElement().getThemeList().add("success");
            case LOAN -> badge.getElement().getThemeList().add("error");
            case INVESTMENT -> badge.getElement().getThemeList().add("contrast");
            case CHECKING -> badge.getElement().getThemeList().add("primary");
            default -> {
                // INSTITUTION uses the default badge style.
            }
        }
        return badge;
    }

    private Span renderBalance(Account account) {
        BigDecimal balance = account.getAccountType() == AccountType.INSTITUTION
                ? institutionTotals.getOrDefault(account.getId(), BigDecimal.ZERO)
                : balancesByAccount.getOrDefault(account.getId(), BigDecimal.ZERO);
        Span span = new Span(formatMoney(balance));
        span.addClassName(balance.signum() < 0 ? LumoUtility.TextColor.ERROR : LumoUtility.TextColor.SUCCESS);
        return span;
    }

    Component renderInterestRate(Account account) {
        if (account.getAccountType() != AccountType.SAVINGS) {
            return new Span();
        }
        BigDecimal currentRate = account.getCurrentInterestRate();
        String formattedRate = currentRate != null ? MoneyFormatter.formatPercent(currentRate, locale()) : "-";
        Span span = new Span(formattedRate);
        span.addClassName(LumoUtility.FontWeight.MEDIUM);

        BigDecimal threshold = appSettingService.getSavingsInterestWarningThreshold();
        boolean below = account.isInterestRateBelow(threshold);

        HorizontalLayout layout = new HorizontalLayout();
        layout.setSpacing(true);
        layout.setJustifyContentMode(com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode.END);
        layout.setDefaultVerticalComponentAlignment(Alignment.CENTER);

        if (below) {
            span.getStyle().set("color", "var(--lumo-warning-text-color, #e65100)");
            span.addClassName(LumoUtility.FontWeight.BOLD);
        }
        layout.add(span);

        String tooltipText = interestTooltip(account);
        layout.getElement().setAttribute("title", tooltipText);
        span.setTitle(tooltipText);
        return layout;
    }

    String interestTooltip(Account account) {
        if (account.getAccountType() != AccountType.SAVINGS) {
            return "";
        }
        String promo = account.getPromotionalInterestRate() != null
                ? MoneyFormatter.formatPercent(account.getPromotionalInterestRate(), locale())
                : "-";
        String until = account.getPromotionalUntil() != null
                ? DateFormatter.format(account.getPromotionalUntil(), locale())
                : "-";
        String subsequent = account.getSubsequentInterestRate() != null
                ? MoneyFormatter.formatPercent(account.getSubsequentInterestRate(), locale())
                : "-";
        return getTranslation("finance.grid.interest.tooltip", promo, until, subsequent);
    }

    void openThresholdDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(getTranslation("finance.threshold.dialog.title"));

        Paragraph desc = new Paragraph(getTranslation("finance.threshold.dialog.description"));
        desc.addClassNames(LumoUtility.TextColor.SECONDARY, LumoUtility.Margin.Top.NONE);

        BigDecimalField thresholdField = new BigDecimalField(getTranslation("finance.threshold.field.threshold"));
        thresholdField.setSuffixComponent(new Span("%"));
        thresholdField.setValue(appSettingService.getSavingsInterestWarningThreshold());
        thresholdField.setWidthFull();

        Button saveButton = new Button(getTranslation("finance.threshold.button.save"), event -> {
            BigDecimal value = thresholdField.getValue();
            if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
                thresholdField.setErrorMessage(getTranslation("finance.editor.error.interest-negative"));
                thresholdField.setInvalid(true);
                return;
            }
            appSettingService.setSavingsInterestWarningThreshold(value);
            dialog.close();
            Notification.show(getTranslation("finance.threshold.notification.saved"), 3000,
                    Notification.Position.MIDDLE);
            refresh();
        });
        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Button cancelButton = new Button(getTranslation("finance.threshold.button.cancel"), event -> dialog.close());

        HorizontalLayout actions = new HorizontalLayout(cancelButton, saveButton);
        actions.setWidthFull();
        actions.setJustifyContentMode(com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode.END);

        VerticalLayout content = new VerticalLayout(desc, thresholdField, actions);
        content.setPadding(false);
        dialog.add(content);
        dialog.setWidth("450px");
        dialog.open();
    }

    private Component renderStatus(Account account) {
        String statusKey = account.isActive()
                ? "finance.account.status.active"
                : "finance.account.status.inactive";
        Span badge = new Span(getTranslation(statusKey));
        badge.getElement().getThemeList().add("badge");
        badge.getElement().getThemeList().add("pill");
        badge.getElement().getThemeList().add(account.isActive() ? "success" : "contrast");
        if (bankSyncService == null || !account.isOpenBankingLinked()) {
            return badge;
        }
        Span consentBadge = createConsentBadge(connectionsById.get(account.getBankConnectionId()));
        if (consentBadge == null) {
            return badge;
        }
        HorizontalLayout statusLayout = new HorizontalLayout(badge, consentBadge);
        statusLayout.setSpacing(true);
        statusLayout.setPadding(false);
        return statusLayout;
    }

    private Span createConsentBadge(BankConnection connection) {
        if (connection == null) {
            return null;
        }
        OffsetDateTime now = OffsetDateTime.now();
        Span badge = new Span();
        badge.getElement().getThemeList().add("badge");
        badge.getElement().getThemeList().add("pill");
        if (connection.isConsentExpired(now)) {
            badge.setText(getTranslation("finance.banksync.badge.expired"));
            badge.getElement().getThemeList().add("error");
            return badge;
        }
        OptionalLong remaining = connection.getRemainingConsentDays(now);
        if (remaining.isPresent()) {
            long days = remaining.getAsLong();
            badge.setText(getTranslation("finance.banksync.badge.valid-days", days));
            boolean expiringSoon = connection.isConsentExpiringSoon(now, BankSyncService.DEFAULT_CONSENT_WARNING_DAYS);
            badge.getElement().getThemeList().add(expiringSoon ? "warning" : "success");
            if (expiringSoon) {
                badge.setTitle(getTranslation("finance.banksync.badge.expiring-hint", days));
            }
            return badge;
        }
        badge.setText(getTranslation("finance.banksync.badge.linked"));
        badge.getElement().getThemeList().add("contrast");
        return badge;
    }

    private HorizontalLayout renderAccountActions(Account account) {
        HorizontalLayout actions = new HorizontalLayout();
        if (positionsDialog != null && account.getAccountType() == AccountType.INVESTMENT) {
            Button positionsButton = GridActionButton.create(
                    VaadinIcon.TRENDING_UP,
                    getTranslation("finance.positions.action.manage"),
                    event -> positionsDialog.openForDepot(account, this::refresh));
            actions.add(positionsButton);
        }
        if (!canModify()) {
            return actions;
        }
        if (bankSyncService != null && account.isOpenBankingLinked()) {
            Button syncButton = GridActionButton.create(
                    VaadinIcon.REFRESH,
                    getTranslation("finance.banksync.action.sync-account"),
                    event -> syncSingleAccount(account));
            actions.add(syncButton);
        }
        Button editButton = GridActionButton.create(
                VaadinIcon.EDIT,
                getTranslation("finance.action.edit"),
                event -> editorDialog.openForEdit(account, this::refresh));

        String toggleKey = account.isActive() ? "finance.action.deactivate" : "finance.action.activate";
        VaadinIcon toggleIcon = account.isActive() ? VaadinIcon.BAN : VaadinIcon.CHECK_CIRCLE_O;
        Button toggleButton = GridActionButton.create(
                toggleIcon,
                getTranslation(toggleKey),
                event -> toggleActive(account));

        actions.add(editButton, toggleButton);
        return actions;
    }

    private void toggleActive(Account account) {
        if (account.isActive()) {
            accountService.deactivate(account.getId());
            Notification.show(getTranslation("finance.notification.deactivated"), 3000,
                    Notification.Position.MIDDLE);
        } else {
            accountService.activate(account.getId());
            Notification.show(getTranslation("finance.notification.activated"), 3000,
                    Notification.Position.MIDDLE);
        }
        refresh();
    }

    private TreeData<Account> buildTreeData(List<Account> accounts) {
        TreeData<Account> treeData = new TreeData<>();
        boolean includeInactive = showInactiveAccounts;

        Map<UUID, List<Account>> allChildrenByParent = accounts.stream()
                .filter(account -> account.getParentId() != null)
                .collect(Collectors.groupingBy(Account::getParentId));

        List<Account> visibleAccounts = includeInactive
                ? accounts
                : accounts.stream()
                        .filter(account -> isVisibleInActiveTree(account, allChildrenByParent))
                        .toList();

        Map<UUID, List<Account>> childrenByParent = visibleAccounts.stream()
                .filter(account -> account.getParentId() != null)
                .collect(Collectors.groupingBy(Account::getParentId));

        List<Account> roots = visibleAccounts.stream()
                .filter(account -> account.getParentId() == null)
                .collect(Collectors.toList());
        treeData.addRootItems(roots);
        addChildrenRecursively(roots, childrenByParent, treeData);
        return treeData;
    }

    private static boolean isVisibleInActiveTree(Account account, Map<UUID, List<Account>> allChildrenByParent) {
        if (!account.isActive()) {
            return false;
        }
        if (account.getAccountType() != AccountType.INSTITUTION) {
            return true;
        }
        List<Account> directChildren = allChildrenByParent.getOrDefault(account.getId(), List.of());
        if (directChildren.isEmpty()) {
            return true;
        }
        return hasActiveDescendant(directChildren, allChildrenByParent);
    }

    private static boolean hasActiveDescendant(List<Account> children, Map<UUID, List<Account>> allChildrenByParent) {
        for (Account child : children) {
            if (child.isActive() && child.getAccountType() != AccountType.INSTITUTION) {
                return true;
            }
            List<Account> grandChildren = allChildrenByParent.getOrDefault(child.getId(), List.of());
            if (child.isActive() && hasActiveDescendant(grandChildren, allChildrenByParent)) {
                return true;
            }
        }
        return false;
    }

    private void addChildrenRecursively(List<Account> parents, Map<UUID, List<Account>> childrenByParent,
            TreeData<Account> treeData) {
        for (Account parent : parents) {
            List<Account> children = childrenByParent.getOrDefault(parent.getId(), List.of());
            treeData.addItems(parent, children);
            addChildrenRecursively(children, childrenByParent, treeData);
        }
    }

    // ---------------------------------------------------------------- shared

    private void configureHouseholdSelector() {
        householdSelector.setItemLabelGenerator(Household::getName);
        householdSelector.setWidth("240px");
        if (householdService == null) {
            householdSelector.setVisible(false);
            return;
        }
        List<Household> accessible = householdService.getAccessibleHouseholdsForCurrentUser();
        householdSelector.setItems(accessible);
        if (!accessible.isEmpty()) {
            UUID defaultId = householdService.resolveEffectiveHouseholdId(null);
            Household initial = accessible.stream()
                    .filter(h -> h.getId().equals(defaultId))
                    .findFirst()
                    .orElse(accessible.get(0));
            householdSelector.setValue(initial);
            selectedHouseholdId = initial.getId();
        }
        householdSelector.setVisible(accessible.size() > 1);
        householdSelector.addValueChangeListener(event -> {
            Household chosen = event.getValue();
            selectedHouseholdId = chosen != null ? chosen.getId() : null;
            refresh();
        });
    }

    private boolean canModify() {
        return householdService == null || householdService.canModifyCurrentHousehold();
    }

    private void refresh() {
        List<Account> accounts = selectedHouseholdId == null
                ? accountService.findAll()
                : accountService.findAllByHousehold(selectedHouseholdId);
        this.currentAccounts = accounts;
        this.balancesByAccount = accountService.resolveCurrentBalances(accounts);
        this.connectionsById = loadConnectionsById();
        List<BalanceDateRow> rows = snapshotService.buildDateRows(selectedHouseholdId);

        chartPanel.setData(accounts, rows, resolveLiveDepotValuations(accounts));
        exitPlanPanel.setHouseholdAccounts(accounts, balancesByAccount);
        updateExitPlanTabVisibility();
        Map<UUID, Account> byId = AccountLabels.byId(accounts);
        List<Account> columns = visibleBalanceAccounts(accounts, rows);
        this.displayedBalanceColumns = columns;
        List<String> signature = balanceColumnSignature(columns, byId);
        if (!signature.equals(configuredBalanceSignature)) {
            if (configuredBalanceSignature != null) {
                // Replace the grid before attaching instead of calling removeAllColumns() +
                // prependHeaderRow()
                // on an attached Vaadin Grid, which corrupts client-side column ordering on
                // refresh.
                balanceGrid = createBalanceGrid();
            }
            rebuildBalanceColumns(columns, byId);
            configuredBalanceSignature = signature;
            if (tabBar.getSelectedTab() == balancesTab) {
                renderSelectedTab();
            }
        }
        balanceGrid.setItems(rows);

        TreeData<Account> treeData = buildTreeData(accounts);
        treeGrid.setTreeData(treeData);
        treeGrid.expand(treeData.getRootItems());
        institutionTotals = accountService.calculateInstitutionTotals();

        netWorthValue.setText(formatMoney(accountService.calculateNetWorth()));
        activeAccountsValue.setText(String.valueOf(accountService.countActiveAccounts()));
        latestDateValue.setText(rows.isEmpty() ? "–" : DateFormatter.format(rows.get(0).date(), locale()));
    }

    private Map<UUID, BankConnection> loadConnectionsById() {
        if (bankSyncService == null) {
            return Map.of();
        }
        Map<UUID, BankConnection> map = new HashMap<>();
        for (BankConnection connection : bankSyncService.findConnectionsByHousehold(selectedHouseholdId)) {
            if (connection.getId() != null) {
                map.put(connection.getId(), connection);
            }
        }
        return map;
    }

    private void openConnectionsFromEditor() {
        if (connectionDialog == null) {
            return;
        }
        connectionDialog.openForHousehold(resolveActiveHouseholdId(), () -> {
            refresh();
            editorDialog.refreshBankConnections(resolveActiveHouseholdId());
        });
    }

    void syncAllLinkedAccounts() {
        if (bankSyncService == null) {
            return;
        }
        try {
            BankSyncResult result = bankSyncService.syncHouseholdAccounts(resolveActiveHouseholdId(), LocalDate.now());
            if (UI.getCurrent() != null) {
                Notification notification = Notification.show(
                        getTranslation("finance.banksync.notification.synced-batch",
                                result.syncedAccounts(), result.skippedAccounts(), result.failedAccounts()),
                        4000,
                        Notification.Position.BOTTOM_START);
                notification.addThemeVariants(result.failedAccounts() > 0
                        ? NotificationVariant.LUMO_CONTRAST
                        : NotificationVariant.LUMO_SUCCESS);
            }
            refresh();
        } catch (RuntimeException ex) {
            if (UI.getCurrent() != null) {
                Notification notification = Notification.show(
                        getTranslation("finance.banksync.notification.error", ex.getMessage()),
                        4000,
                        Notification.Position.MIDDLE);
                notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
            }
        }
    }

    void syncSingleAccount(Account account) {
        if (bankSyncService == null || account == null) {
            return;
        }
        try {
            AccountSnapshot snapshot = bankSyncService.syncAccount(account.getId(), LocalDate.now());
            if (UI.getCurrent() != null) {
                Notification notification = Notification.show(
                        getTranslation("finance.banksync.notification.synced-account",
                                account.getName(), formatMoney(snapshot.getBalance())),
                        4000,
                        Notification.Position.BOTTOM_START);
                notification.addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            }
            refresh();
        } catch (RuntimeException ex) {
            if (UI.getCurrent() != null) {
                Notification notification = Notification.show(
                        getTranslation("finance.banksync.notification.error", ex.getMessage()),
                        4000,
                        Notification.Position.MIDDLE);
                notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
            }
        }
    }

    private Map<UUID, DepotValuation> resolveLiveDepotValuations(List<Account> accounts) {
        if (positionService == null || accounts == null || accounts.isEmpty()) {
            return Map.of();
        }
        List<Account> investments = accounts.stream()
                .filter(Account::isActive)
                .filter(a -> a.getAccountType() == AccountType.INVESTMENT)
                .toList();
        return investments.isEmpty() ? Map.of() : positionService.resolveValuations(investments, Map.of());
    }

    TreeData<Account> treeDataForTest() {
        return treeGrid.getTreeData();
    }

    int treeColumnCountForTest() {
        return treeGrid.getColumns().size();
    }

    void setShowInactiveForTest(boolean value) {
        setShowInactiveAccounts(value);
    }

    Grid<BalanceDateRow> balanceGridForTest() {
        return balanceGrid;
    }

    void selectBalancesTabForTest() {
        tabBar.setSelectedTab(balancesTab);
    }

    void refreshForTest() {
        refresh();
    }

    ActionMenu getActionMenuForTest() {
        return actionMenu;
    }

    HorizontalLayout getKpiRowForTest() {
        return kpiRow;
    }

    boolean isChartTabSelected() {
        return tabBar.getSelectedTab() == chartTab;
    }

    boolean isAccountsTabSelected() {
        return tabBar.getSelectedTab() == accountsTab;
    }

    boolean isExitPlanTabVisibleForTest() {
        return exitPlanTab.isVisible();
    }

    boolean isExitPlanTabSelected() {
        return tabBar.getSelectedTab() == exitPlanTab;
    }

    void toggleExitPlanForTest(boolean enabled) {
        setExitPlanEnabled(enabled);
    }

    private UUID resolveActiveHouseholdId() {
        if (selectedHouseholdId != null) {
            return selectedHouseholdId;
        }
        return householdService != null ? householdService.resolveEffectiveHouseholdId(null) : null;
    }

    private boolean isExitPlanEnabledForSelectedHousehold() {
        if (householdService == null) {
            return false;
        }
        UUID householdId = resolveActiveHouseholdId();
        return householdId != null && householdService.isExitPlanEnabled(householdId);
    }

    private void updateExitPlanTabVisibility() {
        boolean enabled = isExitPlanEnabledForSelectedHousehold();
        exitPlanTab.setVisible(enabled);
        if (!enabled && tabBar.getSelectedTab() == exitPlanTab) {
            tabBar.setSelectedTab(chartTab);
        }
    }

    private void setExitPlanEnabled(boolean enabled) {
        UUID householdId = resolveActiveHouseholdId();
        if (householdService == null || householdId == null) {
            return;
        }
        householdService.setExitPlanEnabled(householdId, enabled);
        exitPlanTab.setVisible(enabled);
        if (enabled) {
            tabBar.setSelectedTab(exitPlanTab);
        } else if (tabBar.getSelectedTab() == exitPlanTab) {
            tabBar.setSelectedTab(chartTab);
        }
        renderSelectedTab();
        refreshMainLayoutNavigation();
    }

    private void refreshMainLayoutNavigation() {
        Component current = getParent().orElse(null);
        while (current != null) {
            if (current instanceof MainLayout mainLayout) {
                mainLayout.refreshNavigation();
                return;
            }
            current = current.getParent().orElse(null);
        }
    }

    Account selectedTreeAccount() {
        return treeGrid.asSingleSelect().getValue();
    }

    int[] hierarchyPathForTest(Account target) {
        TreeData<Account> treeData = treeGrid.getTreeData();
        if (target == null || !treeData.contains(target)) {
            return new int[0];
        }
        Deque<Integer> indices = new ArrayDeque<>();
        Account current = target;
        while (current != null) {
            Account parent = treeData.getParent(current);
            List<Account> siblings = parent == null ? treeData.getRootItems() : treeData.getChildren(parent);
            int idx = siblings.indexOf(current);
            if (idx < 0) {
                return new int[0];
            }
            indices.push(idx);
            current = parent;
        }
        return indices.stream().mapToInt(Integer::intValue).toArray();
    }

    @Override
    public String getTranslation(String key, Object... params) {
        String translation = super.getTranslation(key, params);
        if (translation != null && translation.startsWith("!{") && translation.endsWith("}!")) {
            return new MoneyPolyI18nProvider().getTranslation(key, locale(), params);
        }
        return translation;
    }

    private static String formatMoney(BigDecimal amount) {
        return amount == null ? "" : MoneyFormatter.format(amount, locale());
    }

    private static Locale locale() {
        return UI.getCurrent() != null ? UI.getCurrent().getLocale() : Locale.GERMANY;
    }
}
