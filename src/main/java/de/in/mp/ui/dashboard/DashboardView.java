package de.in.mp.ui.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.dom.Style;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.theme.lumo.LumoUtility;

import de.in.mp.admin.PersonService;
import de.in.mp.finance.Account;
import de.in.mp.finance.AccountService;
import de.in.mp.finance.AccountType;
import de.in.mp.finance.DashboardKpis;
import de.in.mp.finance.DashboardService;
import de.in.mp.finance.Person;
import de.in.mp.i18n.MoneyFormatter;
import de.in.mp.loans.LoanContract;
import de.in.mp.loans.LoanContractService;
import de.in.mp.ui.finance.FinanceView;
import de.in.mp.ui.layout.MainLayout;
import de.in.mp.ui.loans.PrivateLoanView;
import jakarta.annotation.security.PermitAll;

/**
 * Central net worth and asset overview: compact KPI header, structured asset
 * list and drilldown links into the finance and private-loan areas.
 */
@Route(value = "", layout = MainLayout.class)
@PermitAll
public class DashboardView extends VerticalLayout implements HasDynamicTitle {

    private static final String AMOUNT_COLUMN_WIDTH = "140px";
    private static final String PROGRESS_COLUMN_WIDTH = "180px";
    private static final String MAX_CONTENT_WIDTH = "1200px";
    private static final String FLEX_SHRINK = "flex-shrink";
    private static final String NO_SHRINK = "0";
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final DashboardService dashboardService;
    private final AccountService accountService;
    private final LoanContractService loanContractService;
    private final PersonService personService;

    private final Span netWorthValue = new Span();
    private final Span liquidityValue = new Span();
    private final Span investedValue = new Span();
    private final Span loanReceivablesValue = new Span();

    private final Span liquiditySum = createHeaderSum();
    private final Span investedSum = createHeaderSum();
    private final Span loanSum = createHeaderSum();

    private final ProgressIndicator liquidityHeaderProgress = new ProgressIndicator();
    private final ProgressIndicator investedHeaderProgress = new ProgressIndicator();
    private final ProgressIndicator loanHeaderProgress = new ProgressIndicator();

    private final Grid<Account> liquidityGrid = new Grid<>(Account.class, false);
    private final Grid<Account> investmentsGrid = new Grid<>(Account.class, false);
    private final Grid<LoanContract> loansGrid = new Grid<>(LoanContract.class, false);

    private BigDecimal netWorthTotal = BigDecimal.ZERO;
    private BigDecimal liquidityTotal = BigDecimal.ZERO;
    private BigDecimal investedTotal = BigDecimal.ZERO;
    private Map<UUID, String> personNames = Map.of();
    private Map<UUID, Account> accountsById = Map.of();

    public DashboardView(DashboardService dashboardService, AccountService accountService,
            LoanContractService loanContractService, PersonService personService) {
        this.dashboardService = dashboardService;
        this.accountService = accountService;
        this.loanContractService = loanContractService;
        this.personService = personService;

        // Width only: a fixed height would let the flex column shrink the
        // all-rows-visible grids to zero height on the initial render.
        setWidthFull();
        setPadding(true);
        setSpacing(false);
        setMaxWidth(MAX_CONTENT_WIDTH);
        getStyle().set("margin", "0 auto");
        getStyle().set("font-variant-numeric", "tabular-nums");

        add(createHeader(), createKpiRow(), createLiquidityBlock(), createInvestmentsBlock(), createLoansBlock());
        configureGrids();
        refresh();
    }

    @Override
    public String getPageTitle() {
        return getTranslation("dashboard.view.page-title");
    }

    private HorizontalLayout createHeader() {
        H2 title = new H2(getTranslation("dashboard.view.title"));
        title.addClassName(LumoUtility.Margin.NONE);
        return new HorizontalLayout(title);
    }

    private HorizontalLayout createKpiRow() {
        VerticalLayout netWorthCard = createKpiCard(getTranslation("dashboard.kpi.net-worth"), netWorthValue);
        VerticalLayout liquidityCard = createKpiCard(getTranslation("dashboard.kpi.liquidity"), liquidityValue);
        VerticalLayout investedCard = createKpiCard(getTranslation("dashboard.kpi.invested"), investedValue);
        VerticalLayout receivablesCard = createKpiCard(getTranslation("dashboard.kpi.loan-receivables"),
                loanReceivablesValue);

        HorizontalLayout row = new HorizontalLayout(netWorthCard, liquidityCard, investedCard, receivablesCard);
        row.setWidthFull();
        row.setSpacing(true);
        return row;
    }

    private VerticalLayout createKpiCard(String label, Span value) {
        value.addClassNames(LumoUtility.FontSize.XLARGE, LumoUtility.FontWeight.BOLD);

        Span labelSpan = new Span(label);
        labelSpan.addClassNames(LumoUtility.TextColor.SECONDARY, LumoUtility.FontSize.SMALL);

        VerticalLayout card = new VerticalLayout(labelSpan, value);
        card.setPadding(true);
        card.setSpacing(false);
        card.setMinWidth("170px");
        card.addClassNames(
                LumoUtility.Background.CONTRAST_5,
                LumoUtility.BorderRadius.LARGE,
                LumoUtility.BoxShadow.SMALL);
        return card;
    }

    private VerticalLayout createLiquidityBlock() {
        return createBlock(getTranslation("dashboard.block.liquidity"), liquiditySum, liquidityHeaderProgress,
                liquidityGrid);
    }

    private VerticalLayout createInvestmentsBlock() {
        return createBlock(getTranslation("dashboard.block.investments"), investedSum, investedHeaderProgress,
                investmentsGrid);
    }

    private VerticalLayout createLoansBlock() {
        return createBlock(getTranslation("dashboard.block.loans"), loanSum, loanHeaderProgress, loansGrid);
    }

    /**
     * Section header mirrors the grid columns: the sum sits above the amount
     * column (may grow to the left), the progress indicator occupies exactly
     * the progress column width so it lines up with the row indicators.
     */
    private VerticalLayout createBlock(String title, Span sumValue, ProgressIndicator headerProgress,
            Grid<?> grid) {
        H3 heading = new H3(title);
        heading.addClassName(LumoUtility.Margin.NONE);
        applyEllipsis(heading.getStyle());

        headerProgress.setWidth(PROGRESS_COLUMN_WIDTH);
        headerProgress.setMinWidth(PROGRESS_COLUMN_WIDTH);
        headerProgress.getStyle().set(FLEX_SHRINK, NO_SHRINK);
        headerProgress.getStyle().set("padding-right", "var(--lumo-space-m)");
        headerProgress.getStyle().set("box-sizing", "border-box");

        HorizontalLayout header = new HorizontalLayout(heading, sumValue, headerProgress);
        header.setWidthFull();
        header.setSpacing(false);
        header.setDefaultVerticalComponentAlignment(Alignment.CENTER);
        header.expand(heading);
        header.getStyle().set("margin-top", "var(--lumo-space-l)");

        VerticalLayout block = new VerticalLayout(header, grid);
        block.setPadding(false);
        block.setSpacing(false);
        block.getStyle().set(FLEX_SHRINK, NO_SHRINK);
        return block;
    }

    private void configureGrids() {
        configureLiquidityGrid();
        configureInvestmentsGrid();
        configureLoansGrid();
    }

    private void configureLiquidityGrid() {
        liquidityGrid.addColumn(new ComponentRenderer<>(this::renderAccountLabel))
                .setHeader(getTranslation("dashboard.grid.column.name"))
                .setFlexGrow(1);
        liquidityGrid.addColumn(account -> MoneyFormatter.format(account.getCurrentBalance(), locale()))
                .setHeader(rightAlignedHeader("dashboard.grid.column.balance"))
                .setTextAlign(ColumnTextAlign.END)
                .setWidth(AMOUNT_COLUMN_WIDTH)
                .setFlexGrow(0);
        liquidityGrid.addColumn(new ComponentRenderer<>(
                account -> renderProgress(sharePercent(account.getCurrentBalance()))))
                .setHeader(rightAlignedHeader("dashboard.grid.column.share"))
                .setWidth(PROGRESS_COLUMN_WIDTH)
                .setFlexGrow(0);
        liquidityGrid.addItemClickListener(event -> navigateToAccount(event.getItem()));
        liquidityGrid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_NO_ROW_BORDERS);
        liquidityGrid.setAllRowsVisible(true);
        liquidityGrid.setWidthFull();
        liquidityGrid.getStyle().set(FLEX_SHRINK, NO_SHRINK);
    }

    private void configureInvestmentsGrid() {
        investmentsGrid.addColumn(new ComponentRenderer<>(this::renderDepotLabel))
                .setHeader(getTranslation("dashboard.grid.column.name"))
                .setFlexGrow(1);
        investmentsGrid.addColumn(account -> MoneyFormatter.format(account.getCurrentBalance(), locale()))
                .setHeader(rightAlignedHeader("dashboard.grid.column.balance"))
                .setTextAlign(ColumnTextAlign.END)
                .setWidth(AMOUNT_COLUMN_WIDTH)
                .setFlexGrow(0);
        investmentsGrid.addColumn(new ComponentRenderer<>(
                account -> renderProgress(sharePercent(account.getCurrentBalance()))))
                .setHeader(rightAlignedHeader("dashboard.grid.column.share"))
                .setWidth(PROGRESS_COLUMN_WIDTH)
                .setFlexGrow(0);
        investmentsGrid.addItemClickListener(event -> navigateToAccount(event.getItem()));
        investmentsGrid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_NO_ROW_BORDERS);
        investmentsGrid.setAllRowsVisible(true);
        investmentsGrid.setWidthFull();
        investmentsGrid.getStyle().set(FLEX_SHRINK, NO_SHRINK);
    }

    private void configureLoansGrid() {
        loansGrid.addColumn(new ComponentRenderer<>(this::renderLoanLabel))
                .setHeader(getTranslation("dashboard.grid.column.designation"))
                .setFlexGrow(1);
        loansGrid.addColumn(contract -> MoneyFormatter.format(loanContractService.remainingBalance(contract), locale()))
                .setHeader(rightAlignedHeader("dashboard.grid.column.remaining"))
                .setTextAlign(ColumnTextAlign.END)
                .setWidth(AMOUNT_COLUMN_WIDTH)
                .setFlexGrow(0);
        loansGrid.addColumn(new ComponentRenderer<>(contract -> renderProgress(repaymentPercent(contract))))
                .setHeader(rightAlignedHeader("dashboard.grid.column.progress"))
                .setWidth(PROGRESS_COLUMN_WIDTH)
                .setFlexGrow(0);
        loansGrid.addItemClickListener(event -> navigateTo(PrivateLoanView.class));
        loansGrid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_NO_ROW_BORDERS);
        loansGrid.setAllRowsVisible(true);
        loansGrid.setWidthFull();
        loansGrid.getStyle().set(FLEX_SHRINK, NO_SHRINK);
    }

    private Span rightAlignedHeader(String key) {
        Span header = new Span(getTranslation(key));
        header.setWidthFull();
        header.getStyle().set("text-align", "right");
        return header;
    }

    private Span createHeaderSum() {
        Span sum = new Span();
        sum.setMinWidth(AMOUNT_COLUMN_WIDTH);
        sum.getStyle().set("text-align", "right");
        sum.getStyle().set("white-space", "nowrap");
        sum.getStyle().set(FLEX_SHRINK, NO_SHRINK);
        sum.getStyle().set("padding-right", "var(--lumo-space-m)");
        sum.getStyle().set("box-sizing", "border-box");
        sum.addClassNames(LumoUtility.FontWeight.SEMIBOLD);
        return sum;
    }

    /**
     * The direct parent account (e.g. the operational account in the
     * hub-and-spoke hierarchy) must stay visible, otherwise the dashboard
     * loses the context that the finance tree normally provides.
     */
    private String parentName(Account account) {
        Account parent = accountsById.get(account.getParentId());
        return parent != null ? parent.getName() : null;
    }

    private Component renderAccountLabel(Account account) {
        String typeLabel = account.getAccountType() == null
                ? account.getName()
                : getTranslation("finance.account.type." + account.getAccountType().name());
        String primary = join(" · ", parentName(account), typeLabel, account.getAccountHolder());
        String secondary = maskIban(account.getAccountNumber());
        return renderLabel(primary, secondary);
    }

    private Component renderDepotLabel(Account account) {
        String typeLabel = account.getAccountType() == null
                ? account.getName()
                : getTranslation("finance.account.type." + account.getAccountType().name());
        String primary = join(" · ", parentName(account), typeLabel, account.getAccountHolder());
        String secondary = maskIban(account.getAccountNumber());
        return renderLabel(primary, secondary);
    }

    private Component renderLoanLabel(LoanContract contract) {
        String debtors = renderDebtorNames(contract);
        String secondary = debtors.isBlank() ? "" : getTranslation("dashboard.label.debtors", debtors);
        return renderLabel(contract.getDesignation(), secondary);
    }

    /**
     * Single-line label (primary text plus optional secondary text) that only
     * truncates with an ellipsis if the column is genuinely too narrow;
     * stacking onto a second line is deliberately avoided.
     */
    private Component renderLabel(String primary, String secondary) {
        Span container = new Span();
        container.setWidthFull();
        container.getStyle().set("display", "block");
        applyEllipsis(container.getStyle());

        Span primarySpan = new Span(primary);
        primarySpan.addClassName(LumoUtility.FontWeight.MEDIUM);
        container.add(primarySpan);

        if (secondary != null && !secondary.isBlank()) {
            Span secondarySpan = new Span(" · " + secondary);
            secondarySpan.addClassNames(LumoUtility.TextColor.SECONDARY, LumoUtility.FontSize.SMALL);
            container.add(secondarySpan);
        }
        return container;
    }

    private void applyEllipsis(Style style) {
        style.set("white-space", "nowrap");
        style.set("overflow", "hidden");
        style.set("text-overflow", "ellipsis");
        style.set("min-width", "0");
    }

    private String renderDebtorNames(LoanContract contract) {
        return contract.getDebtorIds().stream()
                .map(personNames::get)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(", "));
    }

    private Component renderProgress(BigDecimal percent) {
        return new ProgressIndicator(percent);
    }

    private void refresh() {
        DashboardKpis kpis = dashboardService.calculateKpis();
        netWorthTotal = kpis.netWorth();
        netWorthValue.setText(MoneyFormatter.format(kpis.netWorth(), locale()));
        liquidityValue.setText(MoneyFormatter.format(kpis.liquidity(), locale()));
        investedValue.setText(MoneyFormatter.format(kpis.investedAssets(), locale()));
        loanReceivablesValue.setText(MoneyFormatter.format(kpis.loanReceivables(), locale()));

        List<Account> allAccounts = accountService.findAll();
        accountsById = allAccounts.stream().collect(Collectors.toMap(Account::getId, account -> account));

        List<Account> activeAccounts = allAccounts.stream().filter(Account::isActive).toList();
        List<Account> liquidity = activeAccounts.stream()
                .filter(account -> account.getAccountType() == AccountType.CHECKING
                        || account.getAccountType() == AccountType.SAVINGS)
                .toList();
        List<Account> investments = activeAccounts.stream()
                .filter(account -> account.getAccountType() == AccountType.INVESTMENT)
                .toList();

        liquidityTotal = liquidity.stream()
                .map(Account::getCurrentBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        investedTotal = investments.stream()
                .map(Account::getCurrentBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        personNames = personService.findAll().stream()
                .collect(Collectors.toMap(Person::getId, Person::getName));

        liquidityGrid.setItems(liquidity);
        investmentsGrid.setItems(investments);
        loansGrid.setItems(loanContractService.findAll().stream()
                .filter(LoanContract::isActive)
                .toList());

        renderBlockHeader(liquiditySum, liquidityHeaderProgress, liquidityTotal);
        renderBlockHeader(investedSum, investedHeaderProgress, investedTotal);
        renderBlockHeader(loanSum, loanHeaderProgress, loanContractService.calculateActiveOutstanding());
    }

    private void renderBlockHeader(Span sumValue, ProgressIndicator headerProgress, BigDecimal total) {
        sumValue.setText(getTranslation("dashboard.block.sum", MoneyFormatter.format(total, locale())));
        headerProgress.setPercent(sharePercent(total));
    }

    private BigDecimal sharePercent(BigDecimal value) {
        if (netWorthTotal == null || netWorthTotal.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return value.multiply(HUNDRED).divide(netWorthTotal, 1, RoundingMode.HALF_UP);
    }

    private BigDecimal repaymentPercent(LoanContract contract) {
        BigDecimal original = contract.getOriginalAmount();
        if (original == null || original.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return loanContractService.totalRepaid(contract)
                .multiply(HUNDRED)
                .divide(original, 1, RoundingMode.HALF_UP);
    }

    private String join(String delimiter, String... parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(delimiter);
            }
            builder.append(part.trim());
        }
        return builder.toString();
    }

    private String maskIban(String accountNumber) {
        if (accountNumber == null || accountNumber.isBlank()) {
            return "";
        }
        if (accountNumber.length() <= 8) {
            return accountNumber;
        }
        return accountNumber.substring(0, 4) + " •••• " + accountNumber.substring(accountNumber.length() - 4);
    }

    private void navigateToAccount(Account account) {
        if (account == null || account.getId() == null) {
            navigateTo(FinanceView.class);
            return;
        }
        QueryParameters params = QueryParameters.simple(
                Map.of(FinanceView.ACCOUNT_QUERY_PARAM, account.getId().toString()));
        getUI().ifPresent(ui -> ui.navigate(FinanceView.class, params));
    }

    private void navigateTo(Class<? extends Component> target) {
        getUI().ifPresent(ui -> ui.navigate(target));
    }

    private Locale locale() {
        return UI.getCurrent() != null ? UI.getCurrent().getLocale() : Locale.GERMAN;
    }
}
