package de.in.mp.ui.finance;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.theme.lumo.LumoUtility;

import de.in.mp.finance.Account;
import de.in.mp.finance.AccountService;
import de.in.mp.finance.AccountType;
import de.in.mp.ui.layout.MainLayout;
import jakarta.annotation.security.PermitAll;

/**
 * Finance account overview: KPI cards, account grid and the entry point for
 * creating and editing accounts via {@link AccountEditorDialog}.
 */
@Route(value = "finance", layout = MainLayout.class)
@PermitAll
public class FinanceView extends VerticalLayout implements HasDynamicTitle {

    private final AccountService accountService;
    private final AccountEditorDialog editorDialog;

    private final Grid<Account> grid = new Grid<>(Account.class, false);
    private final Span netWorthValue = new Span();
    private final Span activeAccountsValue = new Span();

    public FinanceView(AccountService accountService) {
        this.accountService = accountService;
        this.editorDialog = new AccountEditorDialog(accountService);

        setSizeFull();
        setPadding(true);
        setSpacing(false);

        add(createHeader(), createKpiRow(), grid);
        configureGrid();
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

    private void configureGrid() {
        grid.addColumn(Account::getName)
                .setHeader(getTranslation("finance.grid.column.name"))
                .setAutoWidth(true)
                .setSortable(true);

        grid.addColumn(account -> account.getBankName() != null ? account.getBankName() : "")
                .setHeader(getTranslation("finance.grid.column.bank"))
                .setAutoWidth(true);

        grid.addColumn(new ComponentRenderer<>(this::renderTypeBadge))
                .setHeader(getTranslation("finance.grid.column.type"))
                .setAutoWidth(true);

        grid.addColumn(account -> account.getAccountNumber() != null ? account.getAccountNumber() : "")
                .setHeader(getTranslation("finance.grid.column.account-number"))
                .setAutoWidth(true);

        grid.addColumn(new ComponentRenderer<>(this::renderBalance))
                .setHeader(getTranslation("finance.grid.column.balance"))
                .setAutoWidth(true);

        grid.addColumn(new ComponentRenderer<>(this::renderStatus))
                .setHeader(getTranslation("finance.grid.column.status"))
                .setAutoWidth(true);

        grid.addColumn(new ComponentRenderer<>(this::renderActions))
                .setHeader(getTranslation("finance.grid.column.actions"))
                .setAutoWidth(true);

        grid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_COLUMN_BORDERS);
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
        }
        return badge;
    }

    private Span renderBalance(Account account) {
        BigDecimal balance = account.getCurrentBalance();
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
        Button editButton = new Button(getTranslation("finance.action.edit"),
                event -> editorDialog.openForEdit(account, this::refresh));
        editButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);

        String toggleKey = account.isActive() ? "finance.action.deactivate" : "finance.action.activate";
        Button toggleButton = new Button(getTranslation(toggleKey), event -> toggleActive(account));
        toggleButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);

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
        grid.setItems(accounts);
        netWorthValue.setText(formatCurrency(accountService.calculateNetWorth()));
        activeAccountsValue.setText(String.valueOf(accountService.countActiveAccounts()));
    }

    private String formatCurrency(BigDecimal amount) {
        Locale locale = UI.getCurrent() != null ? UI.getCurrent().getLocale() : Locale.GERMAN;
        return NumberFormat.getCurrencyInstance(locale).format(amount);
    }
}
