package de.in.mp.ui.loans;

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
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.theme.lumo.LumoUtility;

import de.in.mp.admin.PersonService;
import de.in.mp.finance.Person;
import de.in.mp.i18n.MoneyFormatter;
import de.in.mp.loans.LoanContract;
import de.in.mp.loans.LoanContractService;
import de.in.mp.loans.LoanRepaymentService;
import de.in.mp.ui.GridActionButton;
import de.in.mp.ui.layout.MainLayout;
import jakarta.annotation.security.PermitAll;

/**
 * Overview of private loan contracts: KPI dashboard, contract grid and the
 * entry point for creating/editing contracts and recording repayments.
 */
@Route(value = "loans", layout = MainLayout.class)
@PermitAll
public class PrivateLoanView extends VerticalLayout implements HasDynamicTitle {

    private final LoanContractService loanContractService;
    private final PersonService personService;
    private final LoanContractEditorDialog contractEditorDialog;
    private final LoanRepaymentEditorDialog repaymentEditorDialog;

    private final Grid<LoanContract> grid = new Grid<>(LoanContract.class, false);
    private final Span totalLentValue = new Span();
    private final Span totalRepaidValue = new Span();
    private final Span outstandingValue = new Span();
    private final Span activeContractsValue = new Span();

    private Map<UUID, String> personNames = Map.of();

    public PrivateLoanView(LoanContractService loanContractService,
            LoanRepaymentService loanRepaymentService, PersonService personService) {
        this.loanContractService = loanContractService;
        this.personService = personService;
        this.contractEditorDialog = new LoanContractEditorDialog(loanContractService, personService);
        this.repaymentEditorDialog = new LoanRepaymentEditorDialog(loanRepaymentService, personService);

        setSizeFull();
        setPadding(true);
        setSpacing(false);

        add(createHeader(), createKpiRow(), grid);
        configureGrid();
        refresh();
    }

    @Override
    public String getPageTitle() {
        return getTranslation("loans.view.page-title");
    }

    private HorizontalLayout createHeader() {
        H2 title = new H2(getTranslation("loans.view.title"));
        title.addClassName(LumoUtility.Margin.NONE);

        Button newContractButton = new Button(getTranslation("loans.button.new-contract"),
                VaadinIcon.PLUS.create());
        newContractButton.setTooltipText(getTranslation("loans.button.new-contract"));
        newContractButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        newContractButton.addClickListener(event -> contractEditorDialog.openForCreate(this::refresh));

        HorizontalLayout header = new HorizontalLayout(title, newContractButton);
        header.setWidthFull();
        header.setDefaultVerticalComponentAlignment(Alignment.CENTER);
        header.expand(title);
        header.addClassName(LumoUtility.Margin.Bottom.MEDIUM);
        return header;
    }

    private HorizontalLayout createKpiRow() {
        VerticalLayout totalLentCard = createKpiCard(getTranslation("loans.kpi.total-lent"), totalLentValue);
        VerticalLayout totalRepaidCard = createKpiCard(getTranslation("loans.kpi.total-repaid"), totalRepaidValue);
        VerticalLayout outstandingCard = createKpiCard(getTranslation("loans.kpi.outstanding"), outstandingValue);
        VerticalLayout activeContractsCard = createKpiCard(getTranslation("loans.kpi.active-contracts"),
                activeContractsValue);

        HorizontalLayout row = new HorizontalLayout(totalLentCard, totalRepaidCard, outstandingCard, activeContractsCard);
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
        card.setMinWidth("200px");
        card.addClassNames(
                LumoUtility.Background.CONTRAST_5,
                LumoUtility.BorderRadius.LARGE,
                LumoUtility.BoxShadow.SMALL);
        return card;
    }

    private void configureGrid() {
        grid.addColumn(LoanContract::getDesignation)
                .setHeader(getTranslation("loans.grid.column.designation"))
                .setAutoWidth(true);

        grid.addColumn(this::renderDebtorNames)
                .setHeader(getTranslation("loans.grid.column.debtors"))
                .setAutoWidth(true);

        grid.addColumn(contract -> formatCurrency(contract.getOriginalAmount()))
                .setHeader(getTranslation("loans.grid.column.original-amount"))
                .setAutoWidth(true);

        grid.addColumn(new ComponentRenderer<>(this::renderRepaid))
                .setHeader(getTranslation("loans.grid.column.repaid"))
                .setAutoWidth(true);

        grid.addColumn(contract -> formatCurrency(loanContractService.remainingBalance(contract)))
                .setHeader(getTranslation("loans.grid.column.remaining"))
                .setAutoWidth(true);

        grid.addColumn(new ComponentRenderer<>(this::renderStatus))
                .setHeader(getTranslation("loans.grid.column.status"))
                .setAutoWidth(true);

        grid.addColumn(new ComponentRenderer<>(this::renderActions))
                .setHeader(getTranslation("loans.grid.column.actions"))
                .setAutoWidth(true);

        grid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_COLUMN_BORDERS);
    }

    private String renderDebtorNames(LoanContract contract) {
        return contract.getDebtorIds().stream()
                .map(personNames::get)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(", "));
    }

    private Component renderRepaid(LoanContract contract) {
        BigDecimal repaid = loanContractService.totalRepaid(contract);
        BigDecimal original = contract.getOriginalAmount();

        double progress = 0;
        BigDecimal percent = BigDecimal.ZERO;
        if (original != null && original.signum() > 0) {
            progress = repaid.divide(original, 4, RoundingMode.HALF_UP).doubleValue();
            percent = repaid.multiply(new BigDecimal("100")).divide(original, 1, RoundingMode.HALF_UP);
        }

        ProgressBar bar = new ProgressBar(0, 1, Math.max(0, Math.min(1, progress)));
        Span label = new Span(formatCurrency(repaid) + " (" + percent + "%)");

        VerticalLayout layout = new VerticalLayout(label, bar);
        layout.setPadding(false);
        layout.setSpacing(false);
        return layout;
    }

    private Span renderStatus(LoanContract contract) {
        String key = loanContractService.isPaidOff(contract)
                ? "loans.status.paid-off"
                : "loans.status.active";
        Span badge = new Span(getTranslation(key));
        badge.getElement().getThemeList().add("badge pill");
        badge.getElement().getThemeList().add(loanContractService.isPaidOff(contract) ? "success" : "primary");
        return badge;
    }

    private HorizontalLayout renderActions(LoanContract contract) {
        Button repaymentButton = GridActionButton.create(
                VaadinIcon.COIN_PILES,
                getTranslation("loans.action.record-repayment"),
                event -> repaymentEditorDialog.openForCreate(contract, this::refresh));

        Button editButton = GridActionButton.create(
                VaadinIcon.EDIT,
                getTranslation("loans.action.edit"),
                event -> contractEditorDialog.openForEdit(contract, this::refresh));

        return new HorizontalLayout(repaymentButton, editButton);
    }

    private void refresh() {
        List<LoanContract> contracts = loanContractService.findAll();
        personNames = personService.findAll().stream()
                .collect(Collectors.toMap(Person::getId, Person::getName));

        grid.setItems(contracts);

        totalLentValue.setText(formatCurrency(loanContractService.calculateTotalLentCapital()));
        totalRepaidValue.setText(formatCurrency(loanContractService.calculateTotalRepaid()));
        outstandingValue.setText(formatCurrency(loanContractService.calculateTotalOutstanding()));
        activeContractsValue.setText(String.valueOf(loanContractService.countActiveContracts()));
    }

    private String formatCurrency(BigDecimal amount) {
        Locale locale = UI.getCurrent() != null ? UI.getCurrent().getLocale() : Locale.GERMAN;
        return MoneyFormatter.format(amount, locale);
    }
}
