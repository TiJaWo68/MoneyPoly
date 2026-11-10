package de.in.mp.ui.finance;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.treegrid.TreeGrid;
import com.vaadin.flow.data.provider.hierarchy.TreeData;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.theme.lumo.LumoUtility;

import de.in.mp.finance.Account;
import de.in.mp.finance.AccountService;
import de.in.mp.finance.AccountType;
import de.in.mp.finance.BankLookupService;
import de.in.mp.i18n.MoneyFormatter;
import de.in.mp.ui.GridActionButton;
import de.in.mp.ui.layout.MainLayout;
import jakarta.annotation.security.PermitAll;

/**
 * Finance account overview: KPI cards, hierarchical account tree and the entry
 * point for creating and editing accounts via {@link AccountEditorDialog}.
 */
@Route(value = "finance", layout = MainLayout.class)
@PermitAll
public class FinanceView extends VerticalLayout implements HasDynamicTitle {

    private final AccountService accountService;
    private final AccountEditorDialog editorDialog;

    private final TreeGrid<Account> treeGrid = new TreeGrid<>();
    private final Span netWorthValue = new Span();
    private final Span activeAccountsValue = new Span();

    private Map<UUID, BigDecimal> institutionTotals = Map.of();

    public FinanceView(AccountService accountService, BankLookupService bankLookupService) {
        this.accountService = accountService;
        this.editorDialog = new AccountEditorDialog(accountService, bankLookupService);

        setSizeFull();
        setPadding(true);
        setSpacing(false);

        add(createHeader(), createKpiRow(), treeGrid);
        configureTreeGrid();
        refresh();
    }

    @Override
    public String getPageTitle() {
        return getTranslation("finance.view.page-title");
    }

    private HorizontalLayout createHeader() {
        H2 title = new H2(getTranslation("finance.view.title"));
        title.addClassName(LumoUtility.Margin.NONE);

        Button newAccountButton = new Button(getTranslation("finance.view.button.new-account"),
                VaadinIcon.PLUS.create());
        newAccountButton.setTooltipText(getTranslation("finance.view.button.new-account"));
        newAccountButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        newAccountButton.addClickListener(event -> editorDialog.openForCreate(this::refresh));

        HorizontalLayout header = new HorizontalLayout(title, newAccountButton);
        header.setWidthFull();
        header.setDefaultVerticalComponentAlignment(Alignment.CENTER);
        header.expand(title);
        header.addClassName(LumoUtility.Margin.Bottom.MEDIUM);
        return header;
    }

    private HorizontalLayout createKpiRow() {
        VerticalLayout netWorthCard = createKpiCard(getTranslation("finance.view.kpi.net-worth"), netWorthValue);
        VerticalLayout activeAccountsCard = createKpiCard(getTranslation("finance.view.kpi.active-accounts"),
                activeAccountsValue);

        HorizontalLayout row = new HorizontalLayout(netWorthCard, activeAccountsCard);
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

        treeGrid.addColumn(new ComponentRenderer<>(this::renderBalance))
                .setHeader(getTranslation("finance.grid.column.balance"))
                .setAutoWidth(true);

        treeGrid.addColumn(new ComponentRenderer<>(this::renderStatus))
                .setHeader(getTranslation("finance.grid.column.status"))
                .setAutoWidth(true);

        treeGrid.addColumn(new ComponentRenderer<>(this::renderActions))
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
        Span span = new Span(formatCurrency(balance));
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

    private HorizontalLayout renderActions(Account account) {
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

    private void refresh() {
        List<Account> accounts = accountService.findAll();
        TreeData<Account> treeData = buildTreeData(accounts);
        treeGrid.setTreeData(treeData);
        treeGrid.expand(treeData.getRootItems());

        institutionTotals = accountService.calculateInstitutionTotals();
        netWorthValue.setText(formatCurrency(accountService.calculateNetWorth()));
        activeAccountsValue.setText(String.valueOf(accountService.countActiveAccounts()));
    }

    private TreeData<Account> buildTreeData(List<Account> accounts) {
        TreeData<Account> treeData = new TreeData<>();

        Map<UUID, List<Account>> childrenByParent = accounts.stream()
                .filter(account -> account.getParentId() != null)
                .collect(Collectors.groupingBy(Account::getParentId));

        List<Account> roots = accounts.stream()
                .filter(account -> account.getParentId() == null)
                .collect(Collectors.toList());
        treeData.addRootItems(roots);
        addChildrenRecursively(roots, childrenByParent, treeData);
        return treeData;
    }

    private void addChildrenRecursively(List<Account> parents, Map<UUID, List<Account>> childrenByParent,
            TreeData<Account> treeData) {
        for (Account parent : parents) {
            List<Account> children = childrenByParent.getOrDefault(parent.getId(), List.of());
            treeData.addItems(parent, children);
            addChildrenRecursively(children, childrenByParent, treeData);
        }
    }

    private String formatCurrency(BigDecimal amount) {
        Locale locale = UI.getCurrent() != null ? UI.getCurrent().getLocale() : Locale.GERMAN;
        return MoneyFormatter.format(amount, locale);
    }
}
