package de.in.mp.ui.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasLabel;
import com.vaadin.flow.component.HasSize;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.shared.HasTooltip;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.orderedlayout.FlexLayout.FlexWrap;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.theme.lumo.LumoUtility;

import de.in.mp.finance.Account;
import de.in.mp.finance.AccountType;
import de.in.mp.finance.exitplan.ExitPlanCalculator;
import de.in.mp.finance.exitplan.ExitPlanMode;
import de.in.mp.finance.exitplan.ExitPlanPersonInput;
import de.in.mp.finance.exitplan.ExitPlanRequest;
import de.in.mp.finance.exitplan.ExitPlanResult;
import de.in.mp.finance.exitplan.ExitPlanYearRow;
import de.in.mp.i18n.MoneyFormatter;

/**
 * Interactive prototype workspace for household exit / early-retirement runway
 * planning under Finanzen.
 */
class ExitPlanPanel extends VerticalLayout {

    private static final BigDecimal DEFAULT_RETURN_PERCENT = new BigDecimal("3.50");
    private static final BigDecimal DEFAULT_DESIRED_BUDGET = new BigDecimal("42000.00");
    private static final int DEFAULT_TARGET_AGE = 85;
    private static final int DEFAULT_AGE_PRIMARY = 58;
    private static final int DEFAULT_AGE_SECONDARY = 56;
    private static final int DEFAULT_EXIT_AGE = 62;
    private static final BigDecimal DEFAULT_PENSION = new BigDecimal("1800.00");
    private static final BigDecimal DEFAULT_BRIDGE_KV = new BigDecimal("450.00");
    private static final String TABLE_HEIGHT = "360px";
    private static final String COMPACT_GAP = "0.625rem";
    private static final String THEME_SMALL = "small";
    private static final String WIDTH_CAPITAL = "140px";
    private static final String WIDTH_RESERVE = "125px";
    private static final String WIDTH_PERCENT = "110px";
    private static final String WIDTH_MODE = "225px";
    private static final String WIDTH_AGE = "115px";
    private static final String WIDTH_RESIDUAL = "135px";
    private static final String WIDTH_DESIRED_BUDGET = "150px";
    private static final String WIDTH_NAME = "120px";
    private static final String WIDTH_MONTHLY_MONEY = "120px";
    private static final String WIDTH_MONTHLY_SAVINGS = "125px";
    private static final String WIDTH_BRIDGE_KV = "125px";

    private final ExitPlanCalculator calculator;

    private final Span availableCapitalKpi = new Span("-");
    private final Span annualBudgetKpi = new Span("-");
    private final Span pensionCapitalKpi = new Span("-");
    private final Span targetCapitalKpi = new Span("-");

    private final BigDecimalField liveCapitalField = new BigDecimalField();
    private final BigDecimalField reserveField = new BigDecimalField();
    private final BigDecimalField returnPercentField = new BigDecimalField();
    private final ComboBox<ExitPlanMode> modeSelect = new ComboBox<>();
    private final IntegerField targetAgeField = new IntegerField();
    private final BigDecimalField residualTargetField = new BigDecimalField();
    private final BigDecimalField desiredAnnualBudgetField = new BigDecimalField();

    private final PersonFormSection primaryForm;
    private final Checkbox includeSecondaryCheckbox = new Checkbox();
    private final PersonFormSection secondaryForm;

    private final ExitPlanChartPanel chartPanel = new ExitPlanChartPanel();
    private final Grid<ExitPlanYearRow> yearGrid = new Grid<>(ExitPlanYearRow.class, false);

    private BigDecimal liveAssetsTotal = BigDecimal.ZERO;

    ExitPlanPanel(ExitPlanCalculator calculator) {
        this.calculator = calculator;
        this.primaryForm = new PersonFormSection(
                getTranslation("finance.exit-plan.person.primary.default"),
                DEFAULT_AGE_PRIMARY);
        this.secondaryForm = new PersonFormSection(
                getTranslation("finance.exit-plan.person.secondary.default"),
                DEFAULT_AGE_SECONDARY);

        setWidthFull();
        setPadding(false);
        setSpacing(true);
        getStyle().set("font-variant-numeric", "tabular-nums");

        configureGlobalFields();
        configurePersonSections();
        configureGrid();

        add(createKpiRow(), createParametersCard(), chartPanel, yearGrid);
        recalculate();
    }

    void setHouseholdAccounts(List<Account> accounts, Map<UUID, BigDecimal> balancesByAccount) {
        BigDecimal total = accounts == null ? BigDecimal.ZERO
                : accounts.stream()
                        .filter(Account::isActive)
                        .filter(a -> a.getAccountType() == AccountType.CHECKING
                                || a.getAccountType() == AccountType.SAVINGS
                                || a.getAccountType() == AccountType.INVESTMENT)
                        .map(a -> balancesByAccount != null && a.getId() != null
                                ? balancesByAccount.getOrDefault(a.getId(), BigDecimal.ZERO)
                                : BigDecimal.ZERO)
                        .filter(b -> b != null)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        this.liveAssetsTotal = total.setScale(2, RoundingMode.HALF_UP);
        liveCapitalField.setValue(this.liveAssetsTotal);
        recalculate();
    }

    private HorizontalLayout createKpiRow() {
        HorizontalLayout row = new HorizontalLayout(
                createKpiCard(getTranslation("finance.exit-plan.kpi.available-capital"), availableCapitalKpi),
                createKpiCard(getTranslation("finance.exit-plan.kpi.sustainable-budget"), annualBudgetKpi),
                createKpiCard(getTranslation("finance.exit-plan.kpi.capital-at-pension"), pensionCapitalKpi),
                createKpiCard(getTranslation("finance.exit-plan.kpi.capital-at-target"), targetCapitalKpi));
        row.setWidthFull();
        row.setSpacing(true);
        row.getStyle().set("flex-wrap", "wrap");
        return row;
    }

    private VerticalLayout createKpiCard(String label, Span valueSpan) {
        Span labelSpan = new Span(label);
        labelSpan.addClassNames(LumoUtility.TextColor.SECONDARY, LumoUtility.FontSize.XSMALL);
        valueSpan.addClassNames(LumoUtility.FontSize.LARGE, LumoUtility.FontWeight.BOLD);

        VerticalLayout card = new VerticalLayout(labelSpan, valueSpan);
        card.setPadding(false);
        card.setSpacing(false);
        card.setMinWidth("170px");
        card.getStyle().set("flex", "1 1 170px");
        card.addClassNames(
                LumoUtility.Padding.Vertical.XSMALL,
                LumoUtility.Padding.Horizontal.MEDIUM,
                LumoUtility.Background.CONTRAST_5,
                LumoUtility.BorderRadius.MEDIUM,
                LumoUtility.BoxShadow.SMALL);
        return card;
    }

    private VerticalLayout createParametersCard() {
        VerticalLayout card = new VerticalLayout();
        card.setWidthFull();
        card.setSpacing(false);
        card.getStyle().set("gap", COMPACT_GAP);
        card.addClassNames(
                LumoUtility.Padding.MEDIUM,
                LumoUtility.Background.CONTRAST_5,
                LumoUtility.BorderRadius.LARGE,
                LumoUtility.BoxShadow.SMALL);

        H3 sectionTitle = new H3(getTranslation("finance.exit-plan.section.parameters"));
        sectionTitle.addClassName(LumoUtility.Margin.NONE);

        Button calculateButton = new Button(
                getTranslation("finance.exit-plan.button.calculate"),
                VaadinIcon.CALC_BOOK.create(),
                event -> recalculate());
        calculateButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);

        HorizontalLayout titleBar = new HorizontalLayout(sectionTitle, calculateButton);
        titleBar.setWidthFull();
        titleBar.setDefaultVerticalComponentAlignment(Alignment.CENTER);
        titleBar.expand(sectionTitle);

        FlexLayout globalRow = createCompactRow(
                liveCapitalField,
                reserveField,
                returnPercentField,
                modeSelect,
                targetAgeField,
                residualTargetField,
                desiredAnnualBudgetField);

        card.add(titleBar, globalRow, primaryForm.container, includeSecondaryCheckbox, secondaryForm.container);
        return card;
    }

    private void configureGlobalFields() {
        liveCapitalField.setReadOnly(true);
        liveCapitalField.setValue(BigDecimal.ZERO);
        configureCompactField(liveCapitalField, "finance.exit-plan.field.live-capital", WIDTH_CAPITAL);

        reserveField.setValue(BigDecimal.ZERO);
        configureCompactField(reserveField, "finance.exit-plan.field.reserve", WIDTH_RESERVE);
        reserveField.addValueChangeListener(e -> recalculate());

        returnPercentField.setValue(DEFAULT_RETURN_PERCENT);
        configureCompactField(returnPercentField, "finance.exit-plan.field.return-percent", WIDTH_PERCENT);
        returnPercentField.addValueChangeListener(e -> recalculate());

        modeSelect.setItems(ExitPlanMode.values());
        modeSelect.setItemLabelGenerator(mode -> getTranslation("finance.exit-plan.mode." + mode.name()));
        modeSelect.setValue(ExitPlanMode.DEPLETE_TO_AGE);
        configureCompactField(modeSelect, "finance.exit-plan.field.mode", WIDTH_MODE);
        modeSelect.addValueChangeListener(e -> {
            updateModeFieldVisibility();
            recalculate();
        });

        targetAgeField.setMin(40);
        targetAgeField.setMax(110);
        targetAgeField.setStep(1);
        targetAgeField.setStepButtonsVisible(true);
        targetAgeField.setValue(DEFAULT_TARGET_AGE);
        configureCompactField(targetAgeField, "finance.exit-plan.field.target-age", WIDTH_AGE);
        targetAgeField.addValueChangeListener(e -> recalculate());

        residualTargetField.setValue(BigDecimal.ZERO);
        configureCompactField(residualTargetField, "finance.exit-plan.field.residual-target", WIDTH_RESIDUAL);
        residualTargetField.addValueChangeListener(e -> recalculate());

        desiredAnnualBudgetField.setValue(DEFAULT_DESIRED_BUDGET);
        configureCompactField(
                desiredAnnualBudgetField,
                "finance.exit-plan.field.desired-annual-budget",
                WIDTH_DESIRED_BUDGET);
        desiredAnnualBudgetField.addValueChangeListener(e -> recalculate());

        updateModeFieldVisibility();
    }

    private static FlexLayout createCompactRow(Component... components) {
        FlexLayout row = new FlexLayout(components);
        row.setWidthFull();
        row.setFlexWrap(FlexWrap.WRAP);
        row.setAlignItems(Alignment.END);
        row.getStyle().set("gap", COMPACT_GAP);
        return row;
    }

    private <T extends Component & HasSize & HasLabel & HasTooltip> void configureCompactField(
            T field,
            String baseKey,
            String width) {
        field.setLabel(getTranslation(baseKey));
        field.setTooltipText(getTranslation(baseKey + ".tooltip"));
        field.getElement().getThemeList().add(THEME_SMALL);
        field.setWidth(width);
    }

    private void configurePersonSections() {
        primaryForm.configure(getTranslation("finance.exit-plan.person.primary.title"), this::recalculate);
        includeSecondaryCheckbox.setLabel(getTranslation("finance.exit-plan.field.include-secondary"));
        includeSecondaryCheckbox.setTooltipText(getTranslation("finance.exit-plan.field.include-secondary.tooltip"));
        includeSecondaryCheckbox.setValue(false);
        includeSecondaryCheckbox.addValueChangeListener(e -> {
            secondaryForm.container.setVisible(Boolean.TRUE.equals(e.getValue()));
            recalculate();
        });
        secondaryForm.configure(getTranslation("finance.exit-plan.person.secondary.title"), this::recalculate);
        secondaryForm.container.setVisible(false);
    }

    private void configureGrid() {
        yearGrid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES);
        yearGrid.setWidthFull();
        yearGrid.setHeight(TABLE_HEIGHT);

        yearGrid.addColumn(ExitPlanYearRow::calendarYear)
                .setHeader(getTranslation("finance.exit-plan.grid.year"))
                .setAutoWidth(true);
        yearGrid.addColumn(row -> row.secondaryAge() == null
                ? String.valueOf(row.primaryAge())
                : row.primaryAge() + " / " + row.secondaryAge())
                .setHeader(getTranslation("finance.exit-plan.grid.age"))
                .setAutoWidth(true);
        yearGrid.addColumn(row -> formatMoney(row.workingAndPensionIncome()))
                .setHeader(getTranslation("finance.exit-plan.grid.inflow"))
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);
        yearGrid.addColumn(row -> formatMoney(row.healthInsuranceCost()))
                .setHeader(getTranslation("finance.exit-plan.grid.kv"))
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);
        yearGrid.addColumn(row -> formatMoney(row.livingBudget()))
                .setHeader(getTranslation("finance.exit-plan.grid.budget"))
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);
        yearGrid.addColumn(row -> formatMoney(row.assetWithdrawal()))
                .setHeader(getTranslation("finance.exit-plan.grid.withdrawal"))
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);
        yearGrid.addColumn(row -> formatMoney(row.investmentReturn()))
                .setHeader(getTranslation("finance.exit-plan.grid.return"))
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);
        yearGrid.addColumn(row -> formatMoney(row.endCapital()))
                .setHeader(getTranslation("finance.exit-plan.grid.end-capital"))
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);
    }

    private void updateModeFieldVisibility() {
        ExitPlanMode mode = modeSelect.getValue();
        residualTargetField.setVisible(mode == ExitPlanMode.DEPLETE_TO_AGE);
        desiredAnnualBudgetField.setVisible(mode == ExitPlanMode.FIXED_WITHDRAWAL);
    }

    void recalculate() {
        BigDecimal reserve = nonNegative(reserveField.getValue());
        BigDecimal availableCapital = liveAssetsTotal.subtract(reserve).max(BigDecimal.ZERO);
        BigDecimal returnPercent = nonNegative(returnPercentField.getValue());
        int targetAge = targetAgeField.getValue() == null ? DEFAULT_TARGET_AGE : targetAgeField.getValue();
        ExitPlanMode mode = modeSelect.getValue() == null ? ExitPlanMode.DEPLETE_TO_AGE : modeSelect.getValue();

        ExitPlanRequest request = new ExitPlanRequest(
                availableCapital,
                returnPercent,
                mode,
                targetAge,
                nonNegative(residualTargetField.getValue()),
                nonNegative(desiredAnnualBudgetField.getValue()),
                LocalDate.now().getYear(),
                primaryForm.toInput(),
                Boolean.TRUE.equals(includeSecondaryCheckbox.getValue()) ? secondaryForm.toInput() : null);

        ExitPlanResult result = calculator.calculate(request);
        availableCapitalKpi.setText(formatMoney(availableCapital));
        annualBudgetKpi.setText(formatMoney(result.sustainableAnnualLivingBudget())
                + " (" + formatMoney(result.averageMonthlyLivingBudget()) + ")");
        pensionCapitalKpi.setText(result.capitalAtFirstPension() != null
                ? formatMoney(result.capitalAtFirstPension())
                : "-");
        if (result.exhaustedAtPrimaryAge() != null && result.exhaustedAtPrimaryAge() <= targetAge) {
            targetCapitalKpi.setText(getTranslation("finance.exit-plan.kpi.exhausted-at",
                    result.exhaustedAtPrimaryAge()));
        } else {
            targetCapitalKpi.setText(formatMoney(result.capitalAtTargetAge()));
        }

        chartPanel.setData(request, result);
        yearGrid.setItems(result.rows());
    }

    ExitPlanRequest buildCurrentRequestForTest() {
        BigDecimal availableCapital = liveAssetsTotal.subtract(nonNegative(reserveField.getValue())).max(BigDecimal.ZERO);
        int targetAge = targetAgeField.getValue() == null ? DEFAULT_TARGET_AGE : targetAgeField.getValue();
        return new ExitPlanRequest(
                availableCapital,
                nonNegative(returnPercentField.getValue()),
                modeSelect.getValue(),
                targetAge,
                nonNegative(residualTargetField.getValue()),
                nonNegative(desiredAnnualBudgetField.getValue()),
                LocalDate.now().getYear(),
                primaryForm.toInput(),
                Boolean.TRUE.equals(includeSecondaryCheckbox.getValue()) ? secondaryForm.toInput() : null);
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value == null || value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    private String formatMoney(BigDecimal value) {
        return MoneyFormatter.format(value == null ? BigDecimal.ZERO : value, locale());
    }

    private Locale locale() {
        return UI.getCurrent() != null ? UI.getCurrent().getLocale() : Locale.GERMANY;
    }

    private final class PersonFormSection {

        private final VerticalLayout container = new VerticalLayout();
        private final TextField nameField = new TextField();
        private final IntegerField currentAgeField = new IntegerField();
        private final IntegerField exitAgeField = new IntegerField();
        private final IntegerField pensionAgeField = new IntegerField();
        private final BigDecimalField monthlyPensionField = new BigDecimalField();
        private final BigDecimalField monthlySavingsField = new BigDecimalField();
        private final BigDecimalField monthlyBridgeKvField = new BigDecimalField();
        private final Checkbox familyInsuredCheckbox = new Checkbox();

        private PersonFormSection(String defaultName, int defaultCurrentAge) {
            nameField.setValue(defaultName);
            currentAgeField.setValue(defaultCurrentAge);
            exitAgeField.setValue(Math.max(defaultCurrentAge, DEFAULT_EXIT_AGE));
            pensionAgeField.setValue(ExitPlanPersonInput.DEFAULT_PENSION_AGE);
            monthlyPensionField.setValue(DEFAULT_PENSION);
            monthlySavingsField.setValue(BigDecimal.ZERO);
            monthlyBridgeKvField.setValue(DEFAULT_BRIDGE_KV);
            familyInsuredCheckbox.setValue(true);
        }

        private void configure(String heading, Runnable onChange) {
            container.setWidthFull();
            container.setPadding(false);
            container.setSpacing(false);

            Span title = new Span(heading);
            title.addClassNames(
                    LumoUtility.FontWeight.SEMIBOLD,
                    LumoUtility.FontSize.SMALL,
                    LumoUtility.Margin.Top.XSMALL);

            configureCompactField(nameField, "finance.exit-plan.person.name", WIDTH_NAME);

            configureAgePicker(currentAgeField, "finance.exit-plan.person.current-age", 18, 99, onChange);
            configureAgePicker(exitAgeField, "finance.exit-plan.person.exit-age", 18, 99, onChange);
            configureAgePicker(pensionAgeField, "finance.exit-plan.person.pension-age", 50, 80, onChange);

            configureCompactField(monthlyPensionField, "finance.exit-plan.person.monthly-pension", WIDTH_MONTHLY_MONEY);
            configureCompactField(
                    monthlySavingsField,
                    "finance.exit-plan.person.monthly-savings",
                    WIDTH_MONTHLY_SAVINGS);
            configureCompactField(monthlyBridgeKvField, "finance.exit-plan.person.monthly-bridge-kv", WIDTH_BRIDGE_KV);

            familyInsuredCheckbox.setLabel(getTranslation("finance.exit-plan.person.family-insured"));
            familyInsuredCheckbox.setTooltipText(getTranslation("finance.exit-plan.person.family-insured.tooltip"));
            familyInsuredCheckbox.getStyle().set("margin-bottom", "4px");

            monthlyPensionField.addValueChangeListener(e -> onChange.run());
            monthlySavingsField.addValueChangeListener(e -> onChange.run());
            monthlyBridgeKvField.addValueChangeListener(e -> onChange.run());
            familyInsuredCheckbox.addValueChangeListener(e -> onChange.run());

            FlexLayout row = createCompactRow(
                    nameField,
                    currentAgeField,
                    exitAgeField,
                    pensionAgeField,
                    monthlyPensionField,
                    monthlySavingsField,
                    monthlyBridgeKvField,
                    familyInsuredCheckbox);

            container.add(title, row);
        }

        private void configureAgePicker(
                IntegerField field,
                String labelKey,
                int min,
                int max,
                Runnable onChange) {
            field.setMin(min);
            field.setMax(max);
            field.setStep(1);
            field.setStepButtonsVisible(true);
            configureCompactField(field, labelKey, WIDTH_AGE);
            field.addValueChangeListener(e -> onChange.run());
        }

        private ExitPlanPersonInput toInput() {
            int curAge = currentAgeField.getValue() == null ? DEFAULT_AGE_PRIMARY : currentAgeField.getValue();
            int exitAge = exitAgeField.getValue() == null ? DEFAULT_EXIT_AGE : exitAgeField.getValue();
            int pensionAge = pensionAgeField.getValue() == null
                    ? ExitPlanPersonInput.DEFAULT_PENSION_AGE
                    : pensionAgeField.getValue();
            return new ExitPlanPersonInput(
                    nameField.getValue(),
                    curAge,
                    Math.max(curAge, exitAge),
                    Math.max(curAge, pensionAge),
                    nonNegative(monthlyPensionField.getValue()),
                    nonNegative(monthlySavingsField.getValue()),
                    nonNegative(monthlyBridgeKvField.getValue()),
                    Boolean.TRUE.equals(familyInsuredCheckbox.getValue()));
        }
    }
}
