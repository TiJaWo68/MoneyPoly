package de.in.mp.ui.finance;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.grid.HeaderRow;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
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
    private final AccountSnapshotService snapshotService;
    private final AppSettingService appSettingService;
    private final AccountEditorDialog editorDialog;
    private final BalanceEntryDialog balanceDialog;
    private final StatementImportDialog statementDialog;

    private final Span netWorthValue = new Span();
    private final Span activeAccountsValue = new Span();
    private final Span latestDateValue = new Span();

    private final Tabs tabBar = new Tabs();
    private final Tab chartTab = new Tab();
    private final Tab balancesTab = new Tab();
    private final Tab accountsTab = new Tab();
    private final VerticalLayout tabContent = new VerticalLayout();

    private final BalanceChartPanel chartPanel = new BalanceChartPanel();
    private final Grid<BalanceDateRow> balanceGrid = new Grid<>();
    private final TreeGrid<Account> treeGrid = new TreeGrid<>();
    private final ActionMenu actionMenu;
    private final HorizontalLayout kpiRow;

    private boolean showInactiveAccounts;
    private boolean kpisVisible = true;
    private HeaderRow groupHeaderRow;
    private Map<UUID, BigDecimal> institutionTotals = Map.of();
    private List<Account> currentAccounts = List.of();

    public FinanceView(AccountService accountService, AccountSnapshotService snapshotService,
            BankLookupService bankLookupService, StatementImportService statementImportService,
            AppSettingService appSettingService) {
        this.accountService = accountService;
        this.snapshotService = snapshotService;
        this.appSettingService = appSettingService;
        this.editorDialog = new AccountEditorDialog(accountService, bankLookupService);
        this.balanceDialog = new BalanceEntryDialog(accountService, snapshotService);
        this.statementDialog = new StatementImportDialog(statementImportService, accountService);
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
        tabBar.add(chartTab, balancesTab, accountsTab);
        tabBar.setWidthFull();
        tabBar.addSelectedChangeListener(event -> renderSelectedTab());

        tabContent.setSizeFull();
        tabContent.setPadding(false);
        tabContent.getStyle().set("min-height", "400px");

        configureTreeGrid();
        balanceGrid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_COLUMN_BORDERS,
                GridVariant.LUMO_COMPACT);
        balanceGrid.setSizeFull();
        balanceGrid.getStyle().set("min-height", "380px");
        treeGrid.setSizeFull();
        treeGrid.getStyle().set("min-height", "380px");

        add(createHeader(), kpiRow, tabBar, tabContent);
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
        List<String> values = event.getLocation().getQueryParameters().getParameters().get(ACCOUNT_QUERY_PARAM);
        if (values != null && !values.isEmpty()) {
            try {
                focusAccount(UUID.fromString(values.get(0)));
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed query parameter.
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

    private HorizontalLayout createHeader() {
        H2 title = new H2(getTranslation("finance.view.title"));
        title.addClassNames(LumoUtility.FontSize.XLARGE, LumoUtility.Margin.NONE);

        HorizontalLayout header = new HorizontalLayout(title, actionMenu.getMenuBar());
        header.setWidthFull();
        header.setDefaultVerticalComponentAlignment(Alignment.CENTER);
        header.expand(title);
        header.addClassName(LumoUtility.Margin.Bottom.XSMALL);
        return header;
    }

    private void rebuildActionMenu() {
        actionMenu.clear();
        Tab selected = tabBar.getSelectedTab();

        actionMenu.addAction(
                getTranslation("finance.view.button.record-balances"),
                VaadinIcon.PLUS,
                () -> balanceDialog.openForCreate(this::refresh));
        actionMenu.addAction(
                getTranslation("finance.view.button.import-statement"),
                VaadinIcon.FILE_TEXT_O,
                () -> statementDialog.openForImport(this::refresh));

        if (selected == accountsTab) {
            actionMenu.addSeparator();
            actionMenu.addAction(
                    getTranslation("finance.view.button.new-account"),
                    VaadinIcon.PLUS_CIRCLE_O,
                    () -> editorDialog.openForCreate(this::refresh));
            actionMenu.addAction(
                    getTranslation("finance.view.button.threshold"),
                    VaadinIcon.SLIDERS,
                    this::openThresholdDialog);
        }

        actionMenu.addSeparator();
        if (selected == balancesTab || selected == accountsTab) {
            actionMenu.addToggle(
                    getTranslation("finance.balance.show-inactive"),
                    showInactiveAccounts,
                    this::setShowInactiveAccounts);
        }
        actionMenu.addToggle(
                getTranslation("ui.action-menu.show-kpis"),
                kpisVisible,
                this::setKpisVisible);
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
        kpiRow.setVisible(visible);
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
        } else {
            tabContent.add(balanceGrid);
            tabContent.expand(balanceGrid);
        }
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

    private void rebuildBalanceColumns(List<Account> accounts, List<BalanceDateRow> rows) {
        if (groupHeaderRow != null) {
            balanceGrid.removeHeaderRow(groupHeaderRow);
            groupHeaderRow = null;
        }
        balanceGrid.removeAllColumns();

        Map<UUID, Account> byId = AccountLabels.byId(accounts);
        List<Account> columns = visibleBalanceAccounts(accounts, rows);

        balanceGrid.addColumn(row -> DateFormatter.format(row.date(), locale()))
                .setHeader(getTranslation("finance.balance.field.date"))
                .setFrozen(true)
                .setAutoWidth(true);

        balanceGrid.addColumn(row -> formatMoney(sumOf(row, columns)))
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

        groupHeaderRow = balanceGrid.prependHeaderRow();
        applyGroupedHeaders(groupHeaderRow, accountColumns);
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
                && snapshot.getNotes() == null)) {
            return "";
        }
        return getTranslation("finance.balance.tooltip",
                formatMoney(snapshot.getInflow()),
                formatMoney(snapshot.getOutflow()),
                snapshot.getNotes() == null ? "" : snapshot.getNotes());
    }

    private HorizontalLayout renderRowActions(BalanceDateRow row) {
        Button editButton = GridActionButton.create(VaadinIcon.EDIT,
                getTranslation("finance.balance.action.edit-row"),
                event -> balanceDialog.openForEdit(row, this::refresh));
        Button deleteButton = GridActionButton.createDanger(VaadinIcon.TRASH,
                getTranslation("finance.balance.action.delete-row"),
                event -> {
                    snapshotService.deleteSnapshotsForDate(row.date());
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
                : account.getCurrentBalance();
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

    private Span renderStatus(Account account) {
        String statusKey = account.isActive()
                ? "finance.account.status.active"
                : "finance.account.status.inactive";
        Span badge = new Span(getTranslation(statusKey));
        badge.getElement().getThemeList().add("badge");
        badge.getElement().getThemeList().add("pill");
        badge.getElement().getThemeList().add(account.isActive() ? "success" : "contrast");
        return badge;
    }

    private HorizontalLayout renderAccountActions(Account account) {
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

        return new HorizontalLayout(editButton, toggleButton);
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

    private void refresh() {
        List<Account> accounts = accountService.findAll();
        this.currentAccounts = accounts;
        List<BalanceDateRow> rows = snapshotService.buildDateRows();

        chartPanel.setData(accounts, rows);
        rebuildBalanceColumns(accounts, rows);
        balanceGrid.setItems(rows);

        TreeData<Account> treeData = buildTreeData(accounts);
        treeGrid.setTreeData(treeData);
        treeGrid.expand(treeData.getRootItems());
        institutionTotals = accountService.calculateInstitutionTotals();

        netWorthValue.setText(formatMoney(accountService.calculateNetWorth()));
        activeAccountsValue.setText(String.valueOf(accountService.countActiveAccounts()));
        latestDateValue.setText(rows.isEmpty() ? "–" : DateFormatter.format(rows.get(0).date(), locale()));
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
