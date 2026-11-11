package de.in.mp.ui.finance;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.grid.HeaderRow;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.component.treegrid.TreeGrid;
import com.vaadin.flow.data.provider.hierarchy.TreeData;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.theme.lumo.LumoUtility;

import de.in.mp.finance.Account;
import de.in.mp.finance.AccountService;
import de.in.mp.finance.AccountSnapshot;
import de.in.mp.finance.AccountSnapshotService;
import de.in.mp.finance.AccountType;
import de.in.mp.finance.BalanceDateRow;
import de.in.mp.finance.BankLookupService;
import de.in.mp.i18n.DateFormatter;
import de.in.mp.i18n.MoneyFormatter;
import de.in.mp.importer.StatementImportService;
import de.in.mp.ui.GridActionButton;
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
    private final AccountEditorDialog editorDialog;
    private final BalanceEntryDialog balanceDialog;
    private final StatementImportDialog statementDialog;

    private final Span netWorthValue = new Span();
    private final Span activeAccountsValue = new Span();
    private final Span latestDateValue = new Span();

    private final Tabs tabBar = new Tabs();
    private final Tab balancesTab = new Tab();
    private final Tab accountsTab = new Tab();
    private final VerticalLayout tabContent = new VerticalLayout();

    private final Grid<BalanceDateRow> balanceGrid = new Grid<>();
    private final Checkbox showInactive = new Checkbox();
    private final TreeGrid<Account> treeGrid = new TreeGrid<>();

    private HeaderRow groupHeaderRow;
    private Map<UUID, BigDecimal> institutionTotals = Map.of();
    private List<Account> currentAccounts = List.of();

    public FinanceView(AccountService accountService, AccountSnapshotService snapshotService,
            BankLookupService bankLookupService, StatementImportService statementImportService) {
        this.accountService = accountService;
        this.snapshotService = snapshotService;
        this.editorDialog = new AccountEditorDialog(accountService, bankLookupService);
        this.balanceDialog = new BalanceEntryDialog(accountService, snapshotService);
        this.statementDialog = new StatementImportDialog(statementImportService, accountService);

        setSizeFull();
        setPadding(true);
        setSpacing(false);

        balancesTab.setLabel(getTranslation("finance.tab.balances"));
        accountsTab.setLabel(getTranslation("finance.tab.accounts"));
        tabBar.add(balancesTab, accountsTab);
        tabBar.setWidthFull();
        tabBar.addSelectedChangeListener(event -> renderSelectedTab());

        tabContent.setSizeFull();
        tabContent.setPadding(false);

        showInactive.setLabel(getTranslation("finance.balance.show-inactive"));
        showInactive.addValueChangeListener(event -> refresh());

        configureTreeGrid();
        balanceGrid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_COLUMN_BORDERS,
                GridVariant.LUMO_COMPACT);
        balanceGrid.setSizeFull();
        treeGrid.setSizeFull();

        add(createHeader(), createKpiRow(), tabBar, tabContent);
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

        if (!treeGrid.getTreeData().contains(target) && !Boolean.TRUE.equals(showInactive.getValue())) {
            showInactive.setValue(true);
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
        title.addClassName(LumoUtility.Margin.NONE);

        Button importButton = new Button(getTranslation("finance.view.button.import-statement"),
                VaadinIcon.FILE_TEXT_O.create());
        importButton.setTooltipText(getTranslation("finance.view.button.import-statement"));
        importButton.addClickListener(event -> statementDialog.openForImport(this::refresh));

        Button balanceButton = new Button(getTranslation("finance.view.button.record-balances"),
                VaadinIcon.PLUS.create());
        balanceButton.setTooltipText(getTranslation("finance.view.button.record-balances"));
        balanceButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        balanceButton.addClickListener(event -> balanceDialog.openForCreate(this::refresh));

        HorizontalLayout header = new HorizontalLayout(title, importButton, balanceButton);
        header.setWidthFull();
        header.setDefaultVerticalComponentAlignment(Alignment.CENTER);
        header.expand(title);
        header.addClassName(LumoUtility.Margin.Bottom.MEDIUM);
        return header;
    }

    private HorizontalLayout createKpiRow() {
        HorizontalLayout row = new HorizontalLayout(
                createKpiCard(getTranslation("finance.view.kpi.net-worth"), netWorthValue),
                createKpiCard(getTranslation("finance.view.kpi.active-accounts"), activeAccountsValue),
                createKpiCard(getTranslation("finance.view.kpi.latest-date"), latestDateValue));
        row.setWidthFull();
        row.setSpacing(true);
        row.addClassName(LumoUtility.Margin.Bottom.MEDIUM);
        return row;
    }

    private VerticalLayout createKpiCard(String label, Span value) {
        value.addClassNames(LumoUtility.FontSize.XXLARGE, LumoUtility.FontWeight.BOLD);

        Span labelSpan = new Span(label);
        labelSpan.addClassNames(LumoUtility.TextColor.SECONDARY, LumoUtility.FontSize.SMALL);

        VerticalLayout card = new VerticalLayout(labelSpan, value);
        card.setPadding(true);
        card.setSpacing(false);
        card.setMinWidth("220px");
        card.addClassNames(
                LumoUtility.Background.CONTRAST_5,
                LumoUtility.BorderRadius.LARGE,
                LumoUtility.BoxShadow.SMALL);
        return card;
    }

    private void renderSelectedTab() {
        tabContent.removeAll();
        if (tabBar.getSelectedTab() == accountsTab) {
            Button newAccountButton = new Button(getTranslation("finance.view.button.new-account"),
                    VaadinIcon.PLUS.create(), event -> editorDialog.openForCreate(this::refresh));
            newAccountButton.setTooltipText(getTranslation("finance.view.button.new-account"));
            HorizontalLayout toolbar = new HorizontalLayout(newAccountButton, showInactive);
            toolbar.setDefaultVerticalComponentAlignment(Alignment.CENTER);
            tabContent.add(toolbar, treeGrid);
        } else {
            tabContent.add(showInactive, balanceGrid);
        }
    }

    // ---------------------------------------------------------------- balances

    private void rebuildBalanceColumns(List<Account> accounts, List<BalanceDateRow> rows) {
        if (groupHeaderRow != null) {
            balanceGrid.removeHeaderRow(groupHeaderRow);
            groupHeaderRow = null;
        }
        balanceGrid.removeAllColumns();

        Map<UUID, Account> byId = AccountLabels.byId(accounts);
        List<Account> columns = AccountLabels.matrixOrder(accounts, byId).stream()
                .filter(account -> showInactive.getValue() || account.isActive())
                .filter(account -> account.isActive() || hasSnapshots(account, rows))
                .toList();

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
                    .setHeader(account.getName())
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
        treeGrid.addHierarchyColumn(Account::getName)
                .setHeader(getTranslation("finance.grid.column.name"))
                .setAutoWidth(true);

        treeGrid.addColumn(account -> account.getBankName() != null ? account.getBankName() : "")
                .setHeader(getTranslation("finance.grid.column.bank"))
                .setAutoWidth(true);

        treeGrid.addColumn(new ComponentRenderer<>(this::renderTypeBadge))
                .setHeader(getTranslation("finance.grid.column.type"))
                .setAutoWidth(true);

        treeGrid.addColumn(account -> account.getAccountNumber() != null ? account.getAccountNumber() : "")
                .setHeader(getTranslation("finance.grid.column.account-number"))
                .setAutoWidth(true);

        treeGrid.addColumn(account -> account.getAccountHolder() != null ? account.getAccountHolder() : "")
                .setHeader(getTranslation("finance.grid.column.holder"))
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
        boolean includeInactive = Boolean.TRUE.equals(showInactive.getValue());

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

    void setShowInactiveForTest(boolean value) {
        showInactive.setValue(value);
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

    private static String formatMoney(BigDecimal amount) {
        return amount == null ? "" : MoneyFormatter.format(amount, locale());
    }

    private static Locale locale() {
        return UI.getCurrent() != null ? UI.getCurrent().getLocale() : Locale.GERMANY;
    }
}
