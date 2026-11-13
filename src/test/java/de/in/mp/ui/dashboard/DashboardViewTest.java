package de.in.mp.ui.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.data.event.SortEvent;
import com.vaadin.flow.data.provider.SortDirection;

import de.in.mp.admin.AppSettingService;
import de.in.mp.admin.PersonService;
import de.in.mp.finance.Account;
import de.in.mp.finance.AccountService;
import de.in.mp.finance.AccountType;
import de.in.mp.finance.DashboardKpis;
import de.in.mp.finance.DashboardService;
import de.in.mp.finance.Person;
import de.in.mp.i18n.MoneyPolyI18nProvider;
import de.in.mp.loans.LoanContract;
import de.in.mp.loans.LoanContractService;

class DashboardViewTest {

    private AccountService accountService;
    private LoanContractService loanContractService;
    private PersonService personService;
    private AppSettingService appSettingService;
    private DashboardView view;

    @BeforeEach
    void setUp() {
        DashboardService dashboardService = mock(DashboardService.class);
        accountService = mock(AccountService.class);
        loanContractService = mock(LoanContractService.class);
        personService = mock(PersonService.class);
        appSettingService = mock(AppSettingService.class);

        when(dashboardService.calculateKpis()).thenReturn(
                new DashboardKpis(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        when(accountService.findAll()).thenReturn(List.of());
        when(loanContractService.findAll()).thenReturn(List.of());
        when(loanContractService.calculateActiveOutstanding()).thenReturn(BigDecimal.ZERO);
        when(personService.findAll()).thenReturn(List.of());
        when(appSettingService.getSavingsInterestWarningThreshold()).thenReturn(new BigDecimal("2.00"));

        view = new DashboardView(dashboardService, accountService, loanContractService, personService, appSettingService);
    }

    @Test
    void allColumnsOfAllTablesAreSortable() {
        for (Grid<?> grid : view.allGrids()) {
            for (DashboardSortColumn sortColumn : DashboardSortColumn.values()) {
                assertThat(grid.getColumnByKey(sortColumn.key()).isSortable()).isTrue();
            }
        }
    }

    @Test
    void sortingOneTableByClientClickAppliesSameSortToAllTables() {
        Grid<?> liquidityGrid = view.allGrids().get(0);
        fireClientSort(liquidityGrid, DashboardSortColumn.AMOUNT, SortDirection.DESCENDING);

        for (Grid<?> grid : view.allGrids()) {
            if (grid == liquidityGrid) {
                continue;
            }
            assertThat(grid.getSortOrder()).singleElement().satisfies(order -> {
                assertThat(order.getSorted().getKey()).isEqualTo(DashboardSortColumn.AMOUNT.key());
                assertThat(order.getDirection()).isEqualTo(SortDirection.DESCENDING);
            });
        }
    }

    @Test
    void clearingSortOnOneTableClearsSortOnAllTables() {
        Grid<?> investmentsGrid = view.allGrids().get(1);
        fireClientSort(investmentsGrid, DashboardSortColumn.SHARE, SortDirection.ASCENDING);

        fireClientSortCleared(investmentsGrid);

        for (Grid<?> grid : view.allGrids()) {
            if (grid != investmentsGrid) {
                assertThat(grid.getSortOrder()).isEmpty();
            }
        }
    }

    @Test
    void rendersOnlyFirstNameOfAccountHolderInAccountLabel() {
        Account institution = new Account();
        institution.setId(UUID.randomUUID());
        institution.setName("C24");
        institution.setAccountType(AccountType.INSTITUTION);

        Account checking = new Account();
        checking.setId(UUID.randomUUID());
        checking.setParentId(institution.getId());
        checking.setName("Girokonto");
        checking.setAccountType(AccountType.CHECKING);
        checking.setAccountHolder("Max Mustermann");

        when(accountService.findAll()).thenReturn(List.of(institution, checking));
        DashboardView refreshed = new DashboardView(mockDashboardService(), accountService, loanContractService,
                personService, appSettingService);

        String rendered = refreshed.renderAccountLabel(checking).getElement().getTextRecursively();
        assertThat(rendered).contains("Max").doesNotContain("Mustermann");
    }

    @Test
    void rendersLoanDebtorNamesWithoutDebtorLabelPrefix() {
        Person debtor = new Person();
        debtor.setId(UUID.randomUUID());
        debtor.setName("Person A");
        when(personService.findAll()).thenReturn(List.of(debtor));

        LoanContract contract = new LoanContract();
        contract.setDesignation("Auto");
        contract.setDebtorIds(Set.of(debtor.getId()));

        DashboardView refreshed = new DashboardView(mockDashboardService(), accountService, loanContractService,
                personService, appSettingService);

        String rendered = refreshed.renderLoanLabel(contract).getElement().getTextRecursively();
        assertThat(rendered).isEqualTo("Auto · Person A");
    }

    @Test
    void blockSumTranslationUsesSummationSymbol() {
        MoneyPolyI18nProvider i18n = new MoneyPolyI18nProvider();
        assertThat(i18n.getTranslation("dashboard.block.sum", Locale.GERMAN, "1.000,00 €"))
                .isEqualTo("∑ 1.000,00 €");
        assertThat(i18n.getTranslation("dashboard.block.sum", Locale.ENGLISH, "1,000.00 €"))
                .isEqualTo("∑ 1,000.00 €");
    }

    @Test
    void dashboardKpiAndBlockLabelsAreConcise() {
        MoneyPolyI18nProvider i18n = new MoneyPolyI18nProvider();
        assertThat(i18n.getTranslation("dashboard.kpi.net-worth", Locale.GERMAN)).isEqualTo("Gesamtvermögen");
        assertThat(i18n.getTranslation("dashboard.kpi.invested", Locale.GERMAN)).isEqualTo("Investments");
        assertThat(i18n.getTranslation("dashboard.kpi.loan-receivables", Locale.GERMAN)).isEqualTo("Private-Kredite");
        assertThat(i18n.getTranslation("dashboard.block.investments", Locale.GERMAN)).isEqualTo("Investments");
        assertThat(i18n.getTranslation("dashboard.block.loans", Locale.GERMAN)).isEqualTo("Private-Kredite");

        assertThat(i18n.getTranslation("dashboard.kpi.net-worth", Locale.ENGLISH)).isEqualTo("Net worth");
        assertThat(i18n.getTranslation("dashboard.kpi.invested", Locale.ENGLISH)).isEqualTo("Investments");
        assertThat(i18n.getTranslation("dashboard.kpi.loan-receivables", Locale.ENGLISH)).isEqualTo("Private loans");
        assertThat(i18n.getTranslation("dashboard.block.investments", Locale.ENGLISH)).isEqualTo("Investments");
        assertThat(i18n.getTranslation("dashboard.block.loans", Locale.ENGLISH)).isEqualTo("Private loans");
    }

    @Test
    void savingsAccountBelowWarningThresholdShowsWarning() {
        Account savings = new Account();
        savings.setName("Tagesgeld");
        savings.setAccountType(AccountType.SAVINGS);
        savings.setSubsequentInterestRate(new BigDecimal("1.50"));

        Component comp = view.renderAccountLabel(savings);
        assertThat(comp).isInstanceOf(Span.class);
        Span span = (Span) comp;
        Optional<Component> primary = span.getChildren().findFirst();
        assertThat(primary).isPresent();
        assertThat(primary.get().getStyle().get("color")).isEqualTo("var(--lumo-warning-text-color, #e65100)");

        List<Component> children = span.getChildren().toList();
        assertThat(children).hasSizeGreaterThan(1);
        Span badge = (Span) children.get(1);
        assertThat(badge.getElement().getThemeList()).contains("badge", "pill");
    }

    @Test
    void savingsAccountAtOrAboveWarningThresholdShowsNoWarning() {
        Account savings = new Account();
        savings.setName("Tagesgeld");
        savings.setAccountType(AccountType.SAVINGS);
        savings.setSubsequentInterestRate(new BigDecimal("2.50"));

        Component comp = view.renderAccountLabel(savings);
        assertThat(comp).isInstanceOf(Span.class);
        Span span = (Span) comp;
        Optional<Component> primary = span.getChildren().findFirst();
        assertThat(primary).isPresent();
        assertThat(primary.get().getStyle().get("color")).isNull();
        assertThat(span.getChildren().toList()).hasSize(1);
    }

    @Test
    void checkingAccountShowsNoWarningEvenWithoutInterestRate() {
        Account checking = new Account();
        checking.setName("Girokonto");
        checking.setAccountType(AccountType.CHECKING);

        Component comp = view.renderAccountLabel(checking);
        assertThat(comp).isInstanceOf(Span.class);
        Span span = (Span) comp;
        Optional<Component> primary = span.getChildren().findFirst();
        assertThat(primary).isPresent();
        assertThat(primary.get().getStyle().get("color")).isNull();
        assertThat(span.getChildren().toList()).hasSize(1);
    }

    private static DashboardService mockDashboardService() {
        DashboardService service = mock(DashboardService.class);
        when(service.calculateKpis()).thenReturn(
                new DashboardKpis(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        return service;
    }

    private static <T> void fireClientSort(Grid<T> grid, DashboardSortColumn sortColumn, SortDirection direction) {
        GridSortOrder<T> order = new GridSortOrder<>(grid.getColumnByKey(sortColumn.key()), direction);
        ComponentUtil.fireEvent(grid, new SortEvent<Grid<T>, GridSortOrder<T>>(grid, List.of(order), true));
    }

    private static <T> void fireClientSortCleared(Grid<T> grid) {
        ComponentUtil.fireEvent(grid, new SortEvent<Grid<T>, GridSortOrder<T>>(grid, List.of(), true));
    }
}
