package de.in.mp.ui.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.page.Page;
import com.vaadin.flow.data.provider.SortDirection;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.dom.Style;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import com.vaadin.flow.theme.lumo.LumoUtility;

import de.in.mp.admin.AppSettingService;
import de.in.mp.admin.PersonService;
import de.in.mp.finance.Account;
import de.in.mp.finance.AccountService;
import de.in.mp.finance.AccountSnapshot;
import de.in.mp.finance.AccountSnapshotService;
import de.in.mp.finance.AccountType;
import de.in.mp.finance.DashboardKpis;
import de.in.mp.finance.DashboardService;
import de.in.mp.finance.DepotValuation;
import de.in.mp.finance.HouseholdService;
import de.in.mp.finance.InvestmentPerformance;
import de.in.mp.finance.Person;
import de.in.mp.finance.PortfolioPositionService;
import de.in.mp.i18n.MoneyFormatter;
import de.in.mp.loans.LoanContract;
import de.in.mp.loans.LoanContractService;
import de.in.mp.ui.ResponsiveStyles;
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
    private static final String INFO_COLUMN_WIDTH = "175px";
    private static final String PROGRESS_COLUMN_WIDTH = "180px";
    private static final String MOBILE_INTEREST_COLUMN_WIDTH = "76px";
    private static final String MOBILE_PERFORMANCE_COLUMN_WIDTH = "102px";
    private static final String MOBILE_AMOUNT_COLUMN_WIDTH = "104px";
    private static final String KPI_CARD_MIN_WIDTH = "150px";
    private static final String KPI_CARD_FLEX = "1 1 150px";
    private static final String MAX_CONTENT_WIDTH = "1200px";
    private static final String FLEX_SHRINK = "flex-shrink";
    private static final String NO_SHRINK = "0";
    private static final String EMPTY_PLACEHOLDER = "-";
    private static final String WARNING_TEXT_COLOR = "var(--lumo-warning-text-color, #e65100)";
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final DashboardService dashboardService;
    private final AccountService accountService;
    private final AccountSnapshotService snapshotService;
    private final LoanContractService loanContractService;
    private final PersonService personService;
    private final AppSettingService appSettingService;
    private final HouseholdService householdService;
    private final PortfolioPositionService portfolioPositionService;

    private final Span netWorthValue = new Span();
    private final Span liquidityValue = new Span();
    private final Span investedValue = new Span();
    private final Span loanReceivablesValue = new Span();

    private VerticalLayout netWorthCard;
    private VerticalLayout liquidityCard;
    private VerticalLayout investedCard;
    private VerticalLayout receivablesCard;
    private HorizontalLayout kpiRow;
    private VerticalLayout liquidityBlock;
    private VerticalLayout investmentsBlock;
    private VerticalLayout loansBlock;

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
    private BigDecimal warningThreshold = new BigDecimal("2.00");
    private Map<UUID, String> personNames = Map.of();
    private Map<UUID, Account> accountsById = Map.of();
    private Map<UUID, DepotValuation> depotValuationsByAccount = Map.of();
    private Registration resizeRegistration;
    private Boolean mobileLayoutActive;

    public DashboardView(DashboardService dashboardService, AccountService accountService,
            AccountSnapshotService snapshotService, LoanContractService loanContractService,
            PersonService personService, AppSettingService appSettingService) {
        this(dashboardService, accountService, snapshotService, loanContractService, personService, appSettingService,
                null, null);
    }

    public DashboardView(DashboardService dashboardService, AccountService accountService,
            AccountSnapshotService snapshotService, LoanContractService loanContractService,
            PersonService personService, AppSettingService appSettingService,
            HouseholdService householdService) {
        this(dashboardService, accountService, snapshotService, loanContractService, personService, appSettingService,
                householdService, null);
    }

    @Autowired
    public DashboardView(DashboardService dashboardService, AccountService accountService,
            AccountSnapshotService snapshotService, LoanContractService loanContractService,
            PersonService personService, AppSettingService appSettingService,
            @Autowired(required = false) HouseholdService householdService,
            @Autowired(required = false) PortfolioPositionService portfolioPositionService) {
        this.dashboardService = dashboardService;
        this.accountService = accountService;
        this.snapshotService = snapshotService;
        this.loanContractService = loanContractService;
        this.personService = personService;
        this.appSettingService = appSettingService;
        this.householdService = householdService;
        this.portfolioPositionService = portfolioPositionService;

        // Width only: a fixed height would let the flex column shrink the
        // all-rows-visible grids to zero height on the initial render.
        setWidthFull();
        setPadding(true);
        setSpacing(false);
        setMaxWidth(MAX_CONTENT_WIDTH);
        addClassName(ResponsiveStyles.DASHBOARD_VIEW);
        getStyle().set("margin", "0 auto");
        getStyle().set("font-variant-numeric", "tabular-nums");

        this.kpiRow = createKpiRow();
        this.liquidityBlock = createLiquidityBlock();
        this.investmentsBlock = createInvestmentsBlock();
        this.loansBlock = createLoansBlock();
        add(createHeader(), kpiRow, liquidityBlock, investmentsBlock, loansBlock);
        configureGrids();
        refresh();
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        Page page = attachEvent.getUI().getPage();
        page.retrieveExtendedClientDetails(details -> applyResponsiveLayout(details.getWindowInnerWidth()));
        resizeRegistration = page.addBrowserWindowResizeListener(event -> applyResponsiveLayout(event.getWidth()));
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        if (resizeRegistration != null) {
            resizeRegistration.remove();
            resizeRegistration = null;
        }
        super.onDetach(detachEvent);
    }

    /**
     * Switches grid columns between aligned desktop widths and compact
     * smartphone widths so account names keep maximum horizontal space.
     */
    void applyResponsiveLayout(int viewportWidth) {
        if (viewportWidth <= 0) {
            return;
        }
        boolean mobile = viewportWidth < ResponsiveStyles.MOBILE_BREAKPOINT_PX;
        if (Boolean.valueOf(mobile).equals(mobileLayoutActive)) {
            return;
        }
        mobileLayoutActive = mobile;
        applyGridResponsiveMode(liquidityGrid, mobile ? MOBILE_INTEREST_COLUMN_WIDTH : INFO_COLUMN_WIDTH, mobile);
        applyGridResponsiveMode(investmentsGrid, mobile ? MOBILE_PERFORMANCE_COLUMN_WIDTH : INFO_COLUMN_WIDTH, mobile);
        applyGridResponsiveMode(loansGrid, mobile ? MOBILE_INTEREST_COLUMN_WIDTH : INFO_COLUMN_WIDTH, mobile);
    }

    private void applyGridResponsiveMode(Grid<?> grid, String infoWidth, boolean mobile) {
        Grid.Column<?> infoColumn = grid.getColumnByKey(DashboardSortColumn.INFO.key());
        Grid.Column<?> amountColumn = grid.getColumnByKey(DashboardSortColumn.AMOUNT.key());
        Grid.Column<?> shareColumn = grid.getColumnByKey(DashboardSortColumn.SHARE.key());
        if (infoColumn != null) {
            infoColumn.setWidth(infoWidth);
        }
        if (amountColumn != null) {
            amountColumn.setWidth(mobile ? MOBILE_AMOUNT_COLUMN_WIDTH : AMOUNT_COLUMN_WIDTH);
        }
        if (shareColumn != null) {
            shareColumn.setVisible(!mobile);
            if (!mobile) {
                shareColumn.setWidth(PROGRESS_COLUMN_WIDTH);
            }
        }
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
        netWorthCard = createKpiCard(getTranslation("dashboard.kpi.net-worth"), netWorthValue);
        liquidityCard = createKpiCard(getTranslation("dashboard.kpi.liquidity"), liquidityValue);
        investedCard = createKpiCard(getTranslation("dashboard.kpi.invested"), investedValue);
        receivablesCard = createKpiCard(getTranslation("dashboard.kpi.loan-receivables"),
                loanReceivablesValue);

        HorizontalLayout row = new HorizontalLayout(netWorthCard, liquidityCard, investedCard, receivablesCard);
        row.setWidthFull();
        row.setSpacing(true);
        row.getStyle().set("flex-wrap", "wrap");
        row.addClassName(ResponsiveStyles.HIDE_ON_SHORT_SCREEN);
        return row;
    }

    private VerticalLayout createKpiCard(String label, Span value) {
        value.addClassNames(LumoUtility.FontSize.XLARGE, LumoUtility.FontWeight.BOLD);

        Span labelSpan = new Span(label);
        labelSpan.addClassNames(LumoUtility.TextColor.SECONDARY, LumoUtility.FontSize.SMALL);

        VerticalLayout card = new VerticalLayout(labelSpan, value);
        card.setPadding(true);
        card.setSpacing(false);
        card.setMinWidth(KPI_CARD_MIN_WIDTH);
        card.getStyle().set("flex", KPI_CARD_FLEX);
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
        headerProgress.addClassName(ResponsiveStyles.DASHBOARD_HEADER_PROGRESS);
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
        configureAccountGrid(
                liquidityGrid,
                "dashboard.grid.column.interest",
                this::renderLiquidityInterestRate,
                Comparator.comparing(this::savingsInterestSortKey, Comparator.nullsLast(Comparator.naturalOrder())));
        configureAccountGrid(
                investmentsGrid,
                "dashboard.grid.column.performance",
                this::renderInvestmentPerformance,
                Comparator.comparing(this::investmentPerformanceSortKey,
                        Comparator.nullsLast(Comparator.naturalOrder())));
        configureLoansGrid();
        allGrids().forEach(this::synchronizeSorting);
    }

    /**
     * The share is proportional to the balance, hence both columns sort by
     * the balance.
     */
    private void configureAccountGrid(
            Grid<Account> grid,
            String infoHeaderKey,
            com.vaadin.flow.function.SerializableFunction<Account, Component> infoRenderer,
            Comparator<Account> infoComparator) {
        Comparator<Account> byBalance = Comparator.comparing(Account::getCurrentBalance);
        grid.addColumn(new ComponentRenderer<>(this::renderAccountLabel))
                .setKey(DashboardSortColumn.NAME.key())
                .setComparator(Comparator.comparing(this::accountLabel, String.CASE_INSENSITIVE_ORDER))
                .setHeader(getTranslation("dashboard.grid.column.name"))
                .setFlexGrow(1);
        grid.addColumn(new ComponentRenderer<>(infoRenderer::apply))
                .setKey(DashboardSortColumn.INFO.key())
                .setComparator(infoComparator)
                .setHeader(rightAlignedHeader(infoHeaderKey))
                .setTextAlign(ColumnTextAlign.END)
                .setWidth(INFO_COLUMN_WIDTH)
                .setFlexGrow(0);
        grid.addColumn(new ComponentRenderer<>(this::renderAccountAmount))
                .setKey(DashboardSortColumn.AMOUNT.key())
                .setComparator(byBalance)
                .setHeader(rightAlignedHeader("dashboard.grid.column.balance"))
                .setTextAlign(ColumnTextAlign.END)
                .setWidth(AMOUNT_COLUMN_WIDTH)
                .setFlexGrow(0);
        grid.addColumn(new ComponentRenderer<>(
                account -> renderProgress(sharePercent(account.getCurrentBalance()))))
                .setKey(DashboardSortColumn.SHARE.key())
                .setComparator(byBalance)
                .setHeader(rightAlignedHeader("dashboard.grid.column.share"))
                .setWidth(PROGRESS_COLUMN_WIDTH)
                .setFlexGrow(0);
        grid.addItemClickListener(event -> navigateToAccount(event.getItem()));
        applyCompactLayout(grid);
    }

    private void configureLoansGrid() {
        loansGrid.addColumn(new ComponentRenderer<>(this::renderLoanLabel))
                .setKey(DashboardSortColumn.NAME.key())
                .setComparator(Comparator.comparing(
                        (LoanContract contract) -> Objects.toString(contract.getDesignation(), ""),
                        String.CASE_INSENSITIVE_ORDER))
                .setHeader(getTranslation("dashboard.grid.column.designation"))
                .setFlexGrow(1);
        loansGrid.addColumn(new ComponentRenderer<>(this::renderLoanInterestRate))
                .setKey(DashboardSortColumn.INFO.key())
                .setComparator(Comparator.comparing(
                        this::loanInterestSortKey, Comparator.nullsLast(Comparator.naturalOrder())))
                .setHeader(rightAlignedHeader("dashboard.grid.column.interest"))
                .setTextAlign(ColumnTextAlign.END)
                .setWidth(INFO_COLUMN_WIDTH)
                .setFlexGrow(0);
        loansGrid.addColumn(contract -> MoneyFormatter.format(loanContractService.remainingBalance(contract), locale()))
                .setKey(DashboardSortColumn.AMOUNT.key())
                .setComparator(Comparator.comparing(loanContractService::remainingBalance))
                .setHeader(rightAlignedHeader("dashboard.grid.column.remaining"))
                .setTextAlign(ColumnTextAlign.END)
                .setWidth(AMOUNT_COLUMN_WIDTH)
                .setFlexGrow(0);
        loansGrid.addColumn(new ComponentRenderer<>(contract -> renderProgress(repaymentPercent(contract))))
                .setKey(DashboardSortColumn.SHARE.key())
                .setComparator(Comparator.comparing(this::repaymentPercent))
                .setHeader(rightAlignedHeader("dashboard.grid.column.progress"))
                .setWidth(PROGRESS_COLUMN_WIDTH)
                .setFlexGrow(0);
        loansGrid.addItemClickListener(event -> navigateTo(PrivateLoanView.class));
        applyCompactLayout(loansGrid);
    }

    private static void applyCompactLayout(Grid<?> grid) {
        grid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_NO_ROW_BORDERS);
        grid.addClassName(ResponsiveStyles.DASHBOARD_GRID);
        grid.setAllRowsVisible(true);
        grid.setWidthFull();
        grid.getStyle().set(FLEX_SHRINK, NO_SHRINK);
    }

    /**
     * A sort triggered by a click on a column header is applied to all
     * dashboard tables. Programmatic sorts are ignored to avoid feedback loops.
     */
    private <T> void synchronizeSorting(Grid<T> source) {
        source.addSortListener(event -> {
            if (!event.isFromClient()) {
                return;
            }
            Optional<GridSortOrder<T>> order = event.getSortOrder().stream().findFirst();
            for (Grid<?> target : allGrids()) {
                if (target != source) {
                    applySort(target, order.map(o -> o.getSorted().getKey()).orElse(null),
                            order.map(GridSortOrder::getDirection).orElse(null));
                }
            }
        });
    }

    private static <T> void applySort(Grid<T> grid, String columnKey, SortDirection direction) {
        Grid.Column<T> column = columnKey == null ? null : grid.getColumnByKey(columnKey);
        if (column == null || direction == null) {
            grid.sort(List.of());
            return;
        }
        grid.sort(List.of(new GridSortOrder<>(column, direction)));
    }

    List<Grid<?>> allGrids() {
        return List.of(liquidityGrid, investmentsGrid, loansGrid);
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
        sum.addClassName(ResponsiveStyles.DASHBOARD_HEADER_SUM);
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

    Component renderAccountLabel(Account account) {
        boolean warnInterest = account.getAccountType() == AccountType.SAVINGS
                && account.isInterestRateBelow(warningThreshold);
        return renderAccountLabelComponent(accountLabel(account), maskIban(account.getAccountNumber()), warnInterest,
                account);
    }

    private Component renderAccountLabelComponent(String primary, String secondary, boolean warnInterest,
            Account account) {
        Span container = new Span();
        container.setWidthFull();
        container.getStyle().set("display", "flex");
        container.getStyle().set("align-items", "center");
        container.getStyle().set("gap", "var(--lumo-space-xs)");
        container.getStyle().set("min-width", "0");

        if (account != null) {
            Span typeBadge = renderAccountTypeBadge(account.getAccountType());
            if (typeBadge != null) {
                container.add(typeBadge);
            }
        }

        Span textWrapper = new Span();
        textWrapper.getStyle().set("display", "block");
        textWrapper.getStyle().set("flex", "1 1 0");
        applyEllipsis(textWrapper.getStyle());

        Span primarySpan = new Span(primary);
        primarySpan.addClassName(LumoUtility.FontWeight.MEDIUM);
        if (warnInterest) {
            primarySpan.getStyle().set("color", WARNING_TEXT_COLOR);
            if (account != null) {
                BigDecimal rate = account.getCurrentInterestRate();
                String rateStr = rate != null ? MoneyFormatter.formatPercent(rate, locale()) : EMPTY_PLACEHOLDER;
                container.setTitle(getTranslation("dashboard.savings.warning.tooltip", rateStr,
                        MoneyFormatter.formatPercent(warningThreshold, locale())));
            }
        }
        textWrapper.add(primarySpan);

        if (secondary != null && !secondary.isBlank()) {
            Span secondarySpan = new Span(" · " + secondary);
            secondarySpan.addClassNames(LumoUtility.TextColor.SECONDARY, LumoUtility.FontSize.SMALL);
            textWrapper.add(secondarySpan);
        }
        container.add(textWrapper);
        return container;
    }

    private Span renderAccountTypeBadge(AccountType type) {
        if (type == null) {
            return null;
        }
        String icon;
        String themeVariant;
        switch (type) {
            case CHECKING -> {
                icon = "🪙";
                themeVariant = "primary";
            }
            case SAVINGS -> {
                icon = "🐷";
                themeVariant = "success";
            }
            case INVESTMENT -> {
                icon = "📈";
                themeVariant = "contrast";
            }
            default -> {
                return null;
            }
        }
        Span badge = new Span(icon);
        badge.getElement().getThemeList().add("badge");
        badge.getElement().getThemeList().add("pill");
        badge.getElement().getThemeList().add("small");
        badge.getElement().getThemeList().add(themeVariant);
        badge.getStyle().set(FLEX_SHRINK, NO_SHRINK);
        badge.setTitle(getTranslation("finance.account.type." + type.name()));
        return badge;
    }

    private String accountLabel(Account account) {
        String name = join(" · ", parentName(account), firstName(account.getAccountHolder()));
        return name.isBlank() && account.getName() != null ? account.getName() : name;
    }

    private static String firstName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "";
        }
        return fullName.trim().split("\\s+", 2)[0];
    }

    Component renderLoanLabel(LoanContract contract) {
        return renderLabel(contract.getDesignation(), renderDebtorNames(contract));
    }

    /**
     * Single-line label (primary text plus optional secondary text) that only
     * truncates with an ellipsis if the column is genuinely too narrow;
     * stacking onto a second line is deliberately avoided.
     */
    private Component renderLabel(String primary, String secondary) {
        return renderAccountLabelComponent(primary, secondary, false, null);
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
        boolean hasFinanceAccess = householdService == null || householdService.hasFinanceAccessForCurrentUser();
        boolean hasLenderLoans = householdService == null || householdService.hasLenderLoanAccessForCurrentUser();
        boolean hasBorrowerLoans = householdService != null
                && !hasLenderLoans
                && householdService.hasBorrowerLoanAccessForCurrentUser();

        netWorthCard.setVisible(hasFinanceAccess);
        liquidityCard.setVisible(hasFinanceAccess);
        investedCard.setVisible(hasFinanceAccess);
        liquidityBlock.setVisible(hasFinanceAccess);
        investmentsBlock.setVisible(hasFinanceAccess);

        boolean showLoans = hasLenderLoans || hasBorrowerLoans;
        receivablesCard.setVisible(showLoans);
        loansBlock.setVisible(showLoans);
        kpiRow.setVisible(hasFinanceAccess || showLoans);

        if (appSettingService != null) {
            warningThreshold = appSettingService.getSavingsInterestWarningThreshold();
        }

        if (hasFinanceAccess) {
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

            Map<UUID, AccountSnapshot> latestInvestmentSnapshots = snapshotService.findLatestSnapshots(
                    investments.stream().map(Account::getId).toList());
            depotValuationsByAccount = resolveDepotValuations(investments, latestInvestmentSnapshots);

            personNames = personService.findAll().stream()
                    .collect(Collectors.toMap(Person::getId, Person::getName));

            liquidityGrid.setItems(liquidity);
            investmentsGrid.setItems(investments);
            loansGrid.setItems(loanContractService.findAll().stream()
                    .filter(LoanContract::isActive)
                    .toList());

            renderBlockHeader(liquiditySum, liquidityHeaderProgress, liquidityTotal);
            renderBlockHeader(investedSum, investedHeaderProgress, investedTotal);
            updateInvestmentsPerformanceTooltip(investments);
            renderBlockHeader(loanSum, loanHeaderProgress, loanContractService.calculateActiveOutstanding());
        } else if (hasBorrowerLoans) {
            UUID personId = householdService.getCurrentPersonId().orElse(null);
            List<LoanContract> borrowerContracts = loanContractService.findByBorrower(personId).stream()
                    .filter(LoanContract::isActive)
                    .toList();
            BigDecimal outstanding = loanContractService.calculateActiveOutstanding(borrowerContracts);
            loanReceivablesValue.setText(MoneyFormatter.format(outstanding, locale()));
            personNames = Map.of();
            loansGrid.setItems(borrowerContracts);
            renderBlockHeader(loanSum, loanHeaderProgress, outstanding);
        }
    }

    private Map<UUID, DepotValuation> resolveDepotValuations(
            List<Account> investments,
            Map<UUID, AccountSnapshot> latestSnapshots) {
        if (portfolioPositionService != null) {
            return portfolioPositionService.resolveValuations(investments, latestSnapshots);
        }
        return investments.stream()
                .filter(account -> account.getId() != null)
                .collect(Collectors.toMap(
                        Account::getId,
                        account -> fallbackSnapshotValuation(account, latestSnapshots.get(account.getId()))));
    }

    private static DepotValuation fallbackSnapshotValuation(Account account, AccountSnapshot snapshot) {
        BigDecimal balance = account.getCurrentBalance() == null ? BigDecimal.ZERO : account.getCurrentBalance();
        BigDecimal invested = snapshot != null ? snapshot.getInvestedCapital() : null;
        return new DepotValuation(
                account.getId(),
                false,
                balance,
                invested,
                invested != null ? balance : null,
                invested != null,
                0);
    }

    private void updateInvestmentsPerformanceTooltip(List<Account> investments) {
        BigDecimal balanceWithCostBasis = BigDecimal.ZERO;
        BigDecimal totalInvestedCapital = BigDecimal.ZERO;
        boolean hasAny = false;
        for (Account investment : investments) {
            DepotValuation valuation = depotValuationsByAccount.get(investment.getId());
            if (valuation == null || valuation.investedCapital() == null || valuation.valueWithCostBasis() == null) {
                continue;
            }
            hasAny = true;
            balanceWithCostBasis = balanceWithCostBasis.add(valuation.valueWithCostBasis());
            totalInvestedCapital = totalInvestedCapital.add(valuation.investedCapital());
        }
        if (!hasAny) {
            investedSum.getElement().removeAttribute("title");
            return;
        }
        BigDecimal finalInvested = totalInvestedCapital;
        InvestmentPerformance.of(balanceWithCostBasis, totalInvestedCapital)
                .ifPresent(perf -> investedSum.setTitle(getTranslation("finance.balance.tooltip.invested",
                        MoneyFormatter.format(finalInvested, locale()),
                        perf.format(locale()))));
    }

    Component renderAccountAmount(Account account) {
        Span balanceSpan = new Span(MoneyFormatter.format(effectiveAccountBalance(account), locale()));
        balanceSpan.getStyle().set("white-space", "nowrap");
        return balanceSpan;
    }

    private BigDecimal effectiveAccountBalance(Account account) {
        if (account == null) {
            return BigDecimal.ZERO;
        }
        if (account.getAccountType() == AccountType.INVESTMENT && account.getId() != null) {
            DepotValuation valuation = depotValuationsByAccount.get(account.getId());
            if (valuation != null && valuation.positionBased() && valuation.currentValue() != null) {
                return valuation.currentValue();
            }
        }
        return account.getCurrentBalance() == null ? BigDecimal.ZERO : account.getCurrentBalance();
    }

    Component renderLiquidityInterestRate(Account account) {
        if (account == null || account.getAccountType() != AccountType.SAVINGS) {
            return new Span();
        }
        BigDecimal rate = account.getCurrentInterestRate();
        Span span = new Span(rate != null ? MoneyFormatter.formatPercent(rate, locale()) : EMPTY_PLACEHOLDER);
        span.getStyle().set("white-space", "nowrap");
        if (account.isInterestRateBelow(warningThreshold)) {
            span.getStyle().set("color", WARNING_TEXT_COLOR);
            span.addClassName(LumoUtility.FontWeight.SEMIBOLD);
            String rateStr = rate != null ? MoneyFormatter.formatPercent(rate, locale()) : EMPTY_PLACEHOLDER;
            span.setTitle(getTranslation("dashboard.savings.warning.tooltip", rateStr,
                    MoneyFormatter.formatPercent(warningThreshold, locale())));
        } else if (rate == null) {
            span.addClassName(LumoUtility.TextColor.SECONDARY);
        }
        return span;
    }

    Component renderInvestmentPerformance(Account account) {
        DepotValuation valuation = account == null ? null : depotValuationsByAccount.get(account.getId());
        Optional<InvestmentPerformance> performance = valuation == null
                ? Optional.empty()
                : valuation.performance();
        if (performance.isEmpty()) {
            Span empty = new Span(EMPTY_PLACEHOLDER);
            empty.addClassName(LumoUtility.TextColor.SECONDARY);
            return empty;
        }
        InvestmentPerformance perf = performance.get();
        Span perfSpan = new Span();
        perfSpan.addClassNames(LumoUtility.FontSize.SMALL, ResponsiveStyles.DASHBOARD_PERF_CELL);
        if (perf.isPositive()) {
            perfSpan.addClassName(LumoUtility.TextColor.SUCCESS);
        } else if (perf.isNegative()) {
            perfSpan.addClassName(LumoUtility.TextColor.ERROR);
        } else {
            perfSpan.addClassName(LumoUtility.TextColor.SECONDARY);
        }
        Span gainSpan = new Span(perf.formatGain(locale()));
        gainSpan.getStyle().set("white-space", "nowrap");
        perfSpan.add(gainSpan);
        perf.formatReturnPercent(locale()).ifPresent(pct -> {
            Span percentSpan = new Span(" (" + pct + ")");
            percentSpan.getStyle().set("white-space", "nowrap");
            perfSpan.add(percentSpan);
        });
        StringBuilder tooltip = new StringBuilder(getTranslation("finance.balance.tooltip.invested",
                MoneyFormatter.format(valuation.investedCapital(), locale()),
                perf.format(locale())));
        if (valuation.positionBased()) {
            tooltip.append(" · ").append(getTranslation("finance.positions.mode.positions", valuation.positionCount()));
            if (!valuation.costBasisComplete()) {
                tooltip.append(" (").append(getTranslation("finance.positions.mode.partial-cost-hint")).append(")");
            }
        }
        perfSpan.setTitle(tooltip.toString());
        return perfSpan;
    }

    Component renderLoanInterestRate(LoanContract contract) {
        BigDecimal rate = contract == null ? null : contract.getAnnualInterestRate();
        if (rate == null || rate.signum() <= 0) {
            Span empty = new Span(EMPTY_PLACEHOLDER);
            empty.addClassName(LumoUtility.TextColor.SECONDARY);
            return empty;
        }
        Span span = new Span(MoneyFormatter.formatPercent(rate, locale()));
        span.getStyle().set("white-space", "nowrap");
        return span;
    }

    private BigDecimal savingsInterestSortKey(Account account) {
        if (account == null || account.getAccountType() != AccountType.SAVINGS) {
            return null;
        }
        return account.getCurrentInterestRate();
    }

    private BigDecimal investmentPerformanceSortKey(Account account) {
        if (account == null) {
            return null;
        }
        DepotValuation valuation = depotValuationsByAccount.get(account.getId());
        Optional<InvestmentPerformance> performance = valuation == null
                ? Optional.empty()
                : valuation.performance();
        if (performance.isEmpty()) {
            return null;
        }
        InvestmentPerformance perf = performance.get();
        return perf.returnPercent() != null ? perf.returnPercent() : perf.gain();
    }

    private BigDecimal loanInterestSortKey(LoanContract contract) {
        if (contract == null || contract.getAnnualInterestRate() == null
                || contract.getAnnualInterestRate().signum() <= 0) {
            return null;
        }
        return contract.getAnnualInterestRate();
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
