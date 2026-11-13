package de.in.mp.ui.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.vaadin.flow.data.provider.hierarchy.TreeData;

import de.in.mp.admin.AppSettingService;
import de.in.mp.finance.Account;
import de.in.mp.finance.AccountService;
import de.in.mp.finance.AccountSnapshot;
import de.in.mp.finance.AccountSnapshotService;
import de.in.mp.finance.AccountType;
import de.in.mp.finance.BalanceDateRow;
import de.in.mp.finance.BankLookupService;
import de.in.mp.importer.StatementImportService;

class FinanceViewTest {

    @Test
    void instantiatesAndRebuildsGroupedBalanceColumnsWithoutException() {
        AccountService accountService = mock(AccountService.class);
        AccountSnapshotService snapshotService = mock(AccountSnapshotService.class);
        BankLookupService bankLookupService = mock(BankLookupService.class);
        StatementImportService statementImportService = mock(StatementImportService.class);

        Account c24 = institution(UUID.randomUUID(), "C24");
        Account c24Main = childAccount(UUID.randomUUID(), "Hauptkonto", c24.getId(), 1);
        Account c24Pocket = childAccount(UUID.randomUUID(), "Tagesgeld", c24.getId(), 2);

        Account targobank = institution(UUID.randomUUID(), "Targobank");
        Account targoChecking = childAccount(UUID.randomUUID(), "Giro", targobank.getId(), 3);
        Account targoSavings = childAccount(UUID.randomUUID(), "Sparen", targobank.getId(), 4);

        Account dkb = institution(UUID.randomUUID(), "DKB");
        Account dkbChecking = childAccount(UUID.randomUUID(), "DKB Giro", dkb.getId(), 5);
        Account dkbSavings = childAccount(UUID.randomUUID(), "DKB Sparen", dkb.getId(), 6);

        Account extra = childAccount(UUID.randomUUID(), "Extra", null, 7);

        List<Account> accounts = List.of(
                c24, c24Main, c24Pocket,
                targobank, targoChecking, targoSavings,
                dkb, dkbChecking, dkbSavings,
                extra);
        when(accountService.findAll()).thenReturn(accounts);
        when(accountService.calculateInstitutionTotals()).thenReturn(Map.of());
        when(accountService.calculateNetWorth()).thenReturn(BigDecimal.ZERO);
        when(accountService.countActiveAccounts()).thenReturn(4L);

        LocalDate date = LocalDate.of(2026, 1, 1);
        AccountSnapshot snap = new AccountSnapshot();
        snap.setAccountId(c24Main.getId());
        snap.setSnapshotDate(date);
        snap.setBalance(new BigDecimal("100.00"));
        when(snapshotService.buildDateRows()).thenReturn(List.of(new BalanceDateRow(date, Map.of(c24Main.getId(), snap))));

        assertThatCode(() -> new FinanceView(accountService, snapshotService, bankLookupService, statementImportService, mockAppSettingService()))
                .doesNotThrowAnyException();
    }

    @Test
    void hidesInactiveAccountsAndInactiveOnlyInstitutionsInTreeUntilShowInactiveIsEnabled() {
        AccountService accountService = mock(AccountService.class);
        AccountSnapshotService snapshotService = mock(AccountSnapshotService.class);
        BankLookupService bankLookupService = mock(BankLookupService.class);
        StatementImportService statementImportService = mock(StatementImportService.class);

        Account c24 = institution(UUID.randomUUID(), "C24");
        Account c24Active = childAccount(UUID.randomUUID(), "Giro", c24.getId(), 1);
        Account c24Inactive = childAccount(UUID.randomUUID(), "Pocket", c24.getId(), 2);
        c24Inactive.setActive(false);

        Account bunq = institution(UUID.randomUUID(), "Bunq");
        Account bunqInactive = childAccount(UUID.randomUUID(), "Till", bunq.getId(), 3);
        bunqInactive.setActive(false);

        Account emptyNewInst = institution(UUID.randomUUID(), "Scalable Capital");

        List<Account> accounts = List.of(c24, c24Active, c24Inactive, bunq, bunqInactive, emptyNewInst);
        when(accountService.findAll()).thenReturn(accounts);
        when(accountService.calculateInstitutionTotals()).thenReturn(Map.of());
        when(accountService.calculateNetWorth()).thenReturn(BigDecimal.ZERO);
        when(accountService.countActiveAccounts()).thenReturn(1L);
        when(snapshotService.buildDateRows()).thenReturn(List.of());

        FinanceView view = new FinanceView(accountService, snapshotService, bankLookupService, statementImportService, mockAppSettingService());

        TreeData<Account> activeOnlyTree = view.treeDataForTest();
        assertThat(activeOnlyTree.getRootItems()).containsExactly(c24, emptyNewInst);
        assertThat(activeOnlyTree.getChildren(c24)).containsExactly(c24Active);

        view.setShowInactiveForTest(true);

        TreeData<Account> fullTree = view.treeDataForTest();
        assertThat(fullTree.getRootItems()).containsExactly(c24, bunq, emptyNewInst);
        assertThat(fullTree.getChildren(c24)).containsExactly(c24Active, c24Inactive);
        assertThat(fullTree.getChildren(bunq)).containsExactly(bunqInactive);
    }

    @Test
    void focusAccountSwitchesToAccountsTabAndSelectsTargetAccount() {
        AccountService accountService = mock(AccountService.class);
        AccountSnapshotService snapshotService = mock(AccountSnapshotService.class);
        BankLookupService bankLookupService = mock(BankLookupService.class);
        StatementImportService statementImportService = mock(StatementImportService.class);

        Account c24 = institution(UUID.randomUUID(), "C24");
        Account c24Main = childAccount(UUID.randomUUID(), "Hauptkonto", c24.getId(), 1);

        Account targobank = institution(UUID.randomUUID(), "Targobank");
        Account targoChecking = childAccount(UUID.randomUUID(), "Giro", targobank.getId(), 2);
        Account targoSavings = childAccount(UUID.randomUUID(), "Sparen", targobank.getId(), 3);

        List<Account> accounts = List.of(c24, c24Main, targobank, targoChecking, targoSavings);
        when(accountService.findAll()).thenReturn(accounts);
        when(accountService.calculateInstitutionTotals()).thenReturn(Map.of());
        when(accountService.calculateNetWorth()).thenReturn(BigDecimal.ZERO);
        when(accountService.countActiveAccounts()).thenReturn(3L);
        when(snapshotService.buildDateRows()).thenReturn(List.of());

        FinanceView view = new FinanceView(accountService, snapshotService, bankLookupService, statementImportService, mockAppSettingService());
        assertThat(view.isChartTabSelected()).isTrue();
        assertThat(view.isAccountsTabSelected()).isFalse();

        view.focusAccount(targoSavings.getId());

        assertThat(view.isChartTabSelected()).isFalse();
        assertThat(view.isAccountsTabSelected()).isTrue();
        assertThat(view.selectedTreeAccount()).isEqualTo(targoSavings);
        assertThat(view.hierarchyPathForTest(targoSavings)).containsExactly(1, 1);
    }

    @Test
    void rendersInstitutionWithoutBadgeAndSubAccountWithTypeBadgeInConsolidatedTree() {
        AccountService accountService = mock(AccountService.class);
        AccountSnapshotService snapshotService = mock(AccountSnapshotService.class);
        BankLookupService bankLookupService = mock(BankLookupService.class);
        StatementImportService statementImportService = mock(StatementImportService.class);

        Account c24 = institution(UUID.randomUUID(), "C24");
        Account giro = childAccount(UUID.randomUUID(), "Girokonto", c24.getId(), 1);
        giro.setAccountHolder("Till");

        when(accountService.findAll()).thenReturn(List.of(c24, giro));
        when(accountService.calculateInstitutionTotals()).thenReturn(Map.of());
        when(accountService.calculateNetWorth()).thenReturn(BigDecimal.ZERO);
        when(accountService.countActiveAccounts()).thenReturn(1L);
        when(snapshotService.buildDateRows()).thenReturn(List.of());

        FinanceView view = new FinanceView(accountService, snapshotService, bankLookupService, statementImportService, mockAppSettingService());

        assertThat(view.treeColumnCountForTest()).isEqualTo(7);
        assertThat(view.renderHierarchyCell(c24).getElement().getThemeList()).doesNotContain("badge");
        assertThat(view.renderHierarchyCell(c24).getElement().getText()).isEqualTo("C24");
        assertThat(view.renderHierarchyCell(giro).getElement().getThemeList()).contains("badge");
    }

    @Test
    void rendersTwoLineAccountColumnHeaderUnderInstitutionWithIbanTooltip() {
        AccountService accountService = mock(AccountService.class);
        AccountSnapshotService snapshotService = mock(AccountSnapshotService.class);
        BankLookupService bankLookupService = mock(BankLookupService.class);
        StatementImportService statementImportService = mock(StatementImportService.class);

        Account c24 = institution(UUID.randomUUID(), "C24");
        Account giro = childAccount(UUID.randomUUID(), "Girokonto", c24.getId(), 1);
        giro.setAccountHolder("Till Woitendorf");
        giro.setAccountNumber("DE61500240245446454801");

        when(accountService.findAll()).thenReturn(List.of(c24, giro));
        when(accountService.calculateInstitutionTotals()).thenReturn(Map.of());
        when(accountService.calculateNetWorth()).thenReturn(BigDecimal.ZERO);
        when(accountService.countActiveAccounts()).thenReturn(1L);
        when(snapshotService.buildDateRows()).thenReturn(List.of());

        FinanceView view = new FinanceView(accountService, snapshotService, bankLookupService, statementImportService, mockAppSettingService());

        com.vaadin.flow.component.html.Div header = view.createAccountColumnHeader(giro);
        assertThat(header.getChildren().count()).isEqualTo(2);
        assertThat(header.getElement().getTextRecursively()).contains("Till Woitendorf");
        assertThat(header.getElement().getAttribute("title")).isEqualTo("DE61500240245446454801");
    }

    @Test
    void rendersInterestRateCellForSavingsAccountBelowThreshold() {
        AccountService accountService = mock(AccountService.class);
        AccountSnapshotService snapshotService = mock(AccountSnapshotService.class);
        BankLookupService bankLookupService = mock(BankLookupService.class);
        StatementImportService statementImportService = mock(StatementImportService.class);
        AppSettingService appSettingService = mockAppSettingService();

        Account savings = new Account();
        savings.setId(UUID.randomUUID());
        savings.setName("Tagesgeld");
        savings.setAccountType(AccountType.SAVINGS);
        savings.setSubsequentInterestRate(new BigDecimal("1.50"));

        when(accountService.findAll()).thenReturn(List.of(savings));
        when(accountService.calculateInstitutionTotals()).thenReturn(Map.of());
        when(accountService.calculateNetWorth()).thenReturn(BigDecimal.ZERO);
        when(accountService.countActiveAccounts()).thenReturn(1L);
        when(snapshotService.buildDateRows()).thenReturn(List.of());

        FinanceView view = new FinanceView(accountService, snapshotService, bankLookupService, statementImportService, appSettingService);

        com.vaadin.flow.component.Component cell = view.renderInterestRate(savings);
        assertThat(cell).isInstanceOf(com.vaadin.flow.component.orderedlayout.HorizontalLayout.class);
        com.vaadin.flow.component.orderedlayout.HorizontalLayout layout = (com.vaadin.flow.component.orderedlayout.HorizontalLayout) cell;
        assertThat(layout.getChildren().count()).isEqualTo(1);
        com.vaadin.flow.component.html.Span rateSpan = (com.vaadin.flow.component.html.Span) layout.getChildren().findFirst().orElseThrow();
        assertThat(rateSpan.getStyle().get("color")).isEqualTo("var(--lumo-warning-text-color, #e65100)");
        assertThat(rateSpan.getText()).isEqualTo("1,50 %");
    }

    @Test
    void rendersInterestRateCellForSavingsAccountAtOrAboveThreshold() {
        AccountService accountService = mock(AccountService.class);
        AccountSnapshotService snapshotService = mock(AccountSnapshotService.class);
        BankLookupService bankLookupService = mock(BankLookupService.class);
        StatementImportService statementImportService = mock(StatementImportService.class);
        AppSettingService appSettingService = mockAppSettingService();

        Account savings = new Account();
        savings.setId(UUID.randomUUID());
        savings.setName("Tagesgeld");
        savings.setAccountType(AccountType.SAVINGS);
        savings.setSubsequentInterestRate(new BigDecimal("2.50"));

        when(accountService.findAll()).thenReturn(List.of(savings));
        when(accountService.calculateInstitutionTotals()).thenReturn(Map.of());
        when(accountService.calculateNetWorth()).thenReturn(BigDecimal.ZERO);
        when(accountService.countActiveAccounts()).thenReturn(1L);
        when(snapshotService.buildDateRows()).thenReturn(List.of());

        FinanceView view = new FinanceView(accountService, snapshotService, bankLookupService, statementImportService, appSettingService);

        com.vaadin.flow.component.Component cell = view.renderInterestRate(savings);
        assertThat(cell).isInstanceOf(com.vaadin.flow.component.orderedlayout.HorizontalLayout.class);
        com.vaadin.flow.component.orderedlayout.HorizontalLayout layout = (com.vaadin.flow.component.orderedlayout.HorizontalLayout) cell;
        assertThat(layout.getChildren().count()).isEqualTo(1);
        assertThat(layout.getElement().getTextRecursively()).contains("2,50 %");
    }

    @Test
    void interestTooltipFormatsAllThreeInterestParts() {
        AccountService accountService = mock(AccountService.class);
        AccountSnapshotService snapshotService = mock(AccountSnapshotService.class);
        BankLookupService bankLookupService = mock(BankLookupService.class);
        StatementImportService statementImportService = mock(StatementImportService.class);
        AppSettingService appSettingService = mockAppSettingService();

        Account savings = new Account();
        savings.setId(UUID.randomUUID());
        savings.setName("Tagesgeld");
        savings.setAccountType(AccountType.SAVINGS);
        savings.setPromotionalInterestRate(new BigDecimal("3.50"));
        savings.setPromotionalUntil(LocalDate.of(2026, 6, 30));
        savings.setSubsequentInterestRate(new BigDecimal("1.25"));

        when(accountService.findAll()).thenReturn(List.of(savings));
        when(accountService.calculateInstitutionTotals()).thenReturn(Map.of());
        when(accountService.calculateNetWorth()).thenReturn(BigDecimal.ZERO);
        when(accountService.countActiveAccounts()).thenReturn(1L);
        when(snapshotService.buildDateRows()).thenReturn(List.of());

        FinanceView view = new FinanceView(accountService, snapshotService, bankLookupService, statementImportService, appSettingService);

        String tooltip = view.interestTooltip(savings);
        assertThat(tooltip).contains("3,50 %");
        assertThat(tooltip).contains("30.06.2026");
        assertThat(tooltip).contains("1,25 %");
    }

    @Test
    void actionMenuAdaptsItemsToSelectedTabAndTogglesKpis() {
        AccountService accountService = mock(AccountService.class);
        AccountSnapshotService snapshotService = mock(AccountSnapshotService.class);
        BankLookupService bankLookupService = mock(BankLookupService.class);
        StatementImportService statementImportService = mock(StatementImportService.class);
        AppSettingService appSettingService = mockAppSettingService();

        when(accountService.findAll()).thenReturn(List.of());
        when(accountService.calculateInstitutionTotals()).thenReturn(Map.of());
        when(accountService.calculateNetWorth()).thenReturn(BigDecimal.ZERO);
        when(accountService.countActiveAccounts()).thenReturn(0L);
        when(snapshotService.buildDateRows()).thenReturn(List.of());

        FinanceView view = new FinanceView(accountService, snapshotService, bankLookupService, statementImportService, appSettingService);
        com.vaadin.flow.component.tabs.Tabs tabBar = view.getChildren()
                .filter(com.vaadin.flow.component.tabs.Tabs.class::isInstance)
                .map(com.vaadin.flow.component.tabs.Tabs.class::cast)
                .findFirst()
                .orElseThrow();

        // Tab 0 (History): Record balances, Import statement, Show KPIs
        List<String> historyLabels = view.getActionMenuForTest().getSubMenu().getItems().stream()
                .map(com.vaadin.flow.component.contextmenu.MenuItem::getText)
                .toList();
        assertThat(historyLabels).hasSize(3);

        // Tab 1 (Balances): adds Show inactive accounts
        tabBar.setSelectedTab((com.vaadin.flow.component.tabs.Tab) tabBar.getChildren().toList().get(1));
        List<String> balancesLabels = view.getActionMenuForTest().getSubMenu().getItems().stream()
                .map(com.vaadin.flow.component.contextmenu.MenuItem::getText)
                .toList();
        assertThat(balancesLabels).hasSize(4);

        // Tab 2 (Accounts): adds New account and Interest threshold
        tabBar.setSelectedTab((com.vaadin.flow.component.tabs.Tab) tabBar.getChildren().toList().get(2));
        List<String> accountsLabels = view.getActionMenuForTest().getSubMenu().getItems().stream()
                .map(com.vaadin.flow.component.contextmenu.MenuItem::getText)
                .toList();
        assertThat(accountsLabels).hasSize(6);

        // Toggle KPIs off via the last menu item
        assertThat(view.getKpiRowForTest().isVisible()).isTrue();
        com.vaadin.flow.component.contextmenu.MenuItem kpiToggle = view.getActionMenuForTest().getSubMenu().getItems()
                .get(accountsLabels.size() - 1);
        assertThat(kpiToggle.isCheckable()).isTrue();
        assertThat(kpiToggle.isChecked()).isTrue();
        kpiToggle.setChecked(false);
        com.vaadin.flow.component.ComponentUtil.fireEvent(kpiToggle, new com.vaadin.flow.component.ClickEvent<>(kpiToggle));
        assertThat(view.getKpiRowForTest().isVisible()).isFalse();
    }

    private static AppSettingService mockAppSettingService() {
        AppSettingService service = mock(AppSettingService.class);
        when(service.getSavingsInterestWarningThreshold()).thenReturn(new BigDecimal("2.00"));
        return service;
    }

    private static Account institution(UUID id, String name) {
        Account account = new Account();
        account.setId(id);
        account.setName(name);
        account.setAccountType(AccountType.INSTITUTION);
        account.setActive(true);
        return account;
    }

    private static Account childAccount(UUID id, String name, UUID parentId, int displayOrder) {
        Account account = new Account();
        account.setId(id);
        account.setName(name);
        account.setAccountType(AccountType.CHECKING);
        account.setParentId(parentId);
        account.setDisplayOrder(displayOrder);
        account.setActive(true);
        return account;
    }
}
