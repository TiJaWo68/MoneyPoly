package de.in.mp.ui.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.theme.lumo.LumoUtility;

import de.in.mp.finance.WealthEvent;
import de.in.mp.finance.WealthEventService;
import de.in.mp.i18n.DateFormatter;
import de.in.mp.i18n.MoneyFormatter;
import de.in.mp.loans.LoanContract;
import de.in.mp.loans.LoanContractService;
import de.in.mp.ui.GridActionButton;

/**
 * Dialog for viewing, adding, linking private loans, and removing {@link WealthEvent}
 * chart annotations (explaining dips and spikes on the net worth curve).
 */
class WealthEventDialog extends Dialog {

    private static final int LOAN_SUGGESTION_DAY_TOLERANCE = 3;

    enum EntryMode {
        EXPENSE,
        LOAN_LINK
    }

    enum FlowDirection {
        OUTFLOW,
        INFLOW
    }

    private final WealthEventService wealthEventService;
    private final LoanContractService loanContractService;
    private final boolean canModify;

    private final Div contextBanner = new Div();
    private final Checkbox showAllCheckbox = new Checkbox();
    private final Grid<WealthEvent> grid = new Grid<>(WealthEvent.class, false);
    private final VerticalLayout editorSection = new VerticalLayout();
    private final RadioButtonGroup<EntryMode> modeSelector = new RadioButtonGroup<>();
    private final ComboBox<LoanContract> loanSelector = new ComboBox<>();
    private final Span loanHintSpan = new Span();
    private final DatePicker dateField = new DatePicker();
    private final Select<FlowDirection> directionField = new Select<>();
    private final BigDecimalField amountField = new BigDecimalField();
    private final TextField descriptionField = new TextField();
    private final Button saveButton = new Button();

    private UUID currentHouseholdId;
    private LocalDate intervalPreviousDate;
    private LocalDate intervalTargetDate;
    private Runnable onChanged;
    private List<WealthEvent> allEvents = List.of();
    private List<LoanContract> householdLoans = List.of();
    private Map<UUID, LoanContract> loansById = Map.of();

    WealthEventDialog(
            WealthEventService wealthEventService,
            LoanContractService loanContractService,
            boolean canModify) {
        this.wealthEventService = wealthEventService;
        this.loanContractService = loanContractService;
        this.canModify = canModify;

        setHeaderTitle(getTranslation("finance.wealth-event.dialog.title"));
        setWidth("1080px");
        setMaxWidth("96vw");
        setMaxHeight("92vh");
        setCloseOnEsc(true);
        setCloseOnOutsideClick(false);

        contextBanner.setWidthFull();
        contextBanner.addClassNames(
                LumoUtility.Padding.SMALL,
                LumoUtility.BorderRadius.MEDIUM,
                LumoUtility.Background.CONTRAST_5);

        showAllCheckbox.setLabel(getTranslation("finance.wealth-event.show-all"));
        showAllCheckbox.addValueChangeListener(event -> refreshGridItems());

        configureGrid();
        configureEditorSection();

        Button closeButton = new Button(getTranslation("finance.positions.button.close"), event -> close());
        HorizontalLayout footer = new HorizontalLayout(closeButton);
        footer.setWidthFull();
        footer.setJustifyContentMode(JustifyContentMode.END);

        VerticalLayout content = new VerticalLayout(contextBanner, showAllCheckbox, grid, editorSection, footer);
        content.setWidthFull();
        content.setPadding(false);
        content.setSpacing(true);
        add(content);
    }

    void openForInterval(
            UUID householdId,
            LocalDate previousDate,
            LocalDate targetDate,
            BigDecimal netWorthDelta,
            Runnable onChanged) {
        this.currentHouseholdId = householdId;
        this.intervalPreviousDate = previousDate;
        this.intervalTargetDate = targetDate;
        this.onChanged = onChanged;

        showAllCheckbox.setVisible(true);
        showAllCheckbox.setValue(false);
        renderContextBanner(previousDate, targetDate, netWorthDelta);
        reloadDataAndPrefill();
        open();
    }

    void openOverview(UUID householdId, Runnable onChanged) {
        this.currentHouseholdId = householdId;
        this.intervalPreviousDate = null;
        this.intervalTargetDate = null;
        this.onChanged = onChanged;

        showAllCheckbox.setVisible(false);
        showAllCheckbox.setValue(true);
        contextBanner.removeAll();
        contextBanner.add(new Span(getTranslation("finance.wealth-event.overview.hint")));
        reloadDataAndPrefill();
        open();
    }

    private void reloadDataAndPrefill() {
        this.allEvents = wealthEventService.findByHousehold(currentHouseholdId);
        this.householdLoans = loanContractService != null
                ? loanContractService.findByHousehold(currentHouseholdId)
                : List.of();
        this.loansById = householdLoans.stream()
                .filter(l -> l.getId() != null)
                .collect(Collectors.toMap(LoanContract::getId, l -> l, (a, b) -> a));

        loanSelector.setItems(householdLoans);
        prefillForm();
        refreshGridItems();
    }

    private void prefillForm() {
        editorSection.setVisible(canModify);
        if (!canModify) {
            return;
        }
        loanSelector.clear();
        loanHintSpan.setText("");
        amountField.clear();
        descriptionField.clear();
        directionField.setValue(FlowDirection.OUTFLOW);
        dateField.setValue(intervalTargetDate != null ? intervalTargetDate : LocalDate.now());

        boolean hasLoans = !householdLoans.isEmpty();
        modeSelector.setEnabled(hasLoans);
        LoanContract suggested = findUnlinkedLoanInInterval();
        if (suggested != null) {
            modeSelector.setValue(EntryMode.LOAN_LINK);
            loanSelector.setValue(suggested);
            loanHintSpan.setText(getTranslation("finance.wealth-event.loan.suggested"));
        } else {
            modeSelector.setValue(EntryMode.EXPENSE);
        }
        updateModeVisibility();
    }

    private LoanContract findUnlinkedLoanInInterval() {
        return findUnlinkedLoanInInterval(
                intervalPreviousDate, intervalTargetDate, householdLoans, allEvents);
    }

    static LoanContract findUnlinkedLoanInInterval(
            LocalDate prevExclusive,
            LocalDate targetInclusive,
            List<LoanContract> loans,
            List<WealthEvent> existingEvents) {
        if (loans == null || loans.isEmpty() || targetInclusive == null) {
            return null;
        }
        Set<UUID> alreadyLinked = existingEvents == null
                ? Set.of()
                : existingEvents.stream()
                        .map(WealthEvent::getLoanContractId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());

        for (LoanContract contract : loans) {
            if (contract.getId() == null || alreadyLinked.contains(contract.getId())
                    || contract.getStartDate() == null) {
                continue;
            }
            if (isDateInInterval(contract.getStartDate(), prevExclusive, targetInclusive)) {
                return contract;
            }
        }
        for (LoanContract contract : loans) {
            if (contract.getId() == null || alreadyLinked.contains(contract.getId())
                    || contract.getStartDate() == null) {
                continue;
            }
            if (Math.abs(ChronoUnit.DAYS.between(contract.getStartDate(), targetInclusive))
                    <= LOAN_SUGGESTION_DAY_TOLERANCE) {
                return contract;
            }
        }
        return null;
    }

    private void refreshGridItems() {
        boolean showAll = Boolean.TRUE.equals(showAllCheckbox.getValue()) || intervalTargetDate == null;
        List<WealthEvent> items = showAll
                ? allEvents
                : allEvents.stream()
                        .filter(ev -> isDateInInterval(ev.getEventDate(), intervalPreviousDate, intervalTargetDate))
                        .toList();
        grid.setItems(items);
    }

    private void renderContextBanner(LocalDate previousDate, LocalDate targetDate, BigDecimal delta) {
        contextBanner.removeAll();
        String rangeText = previousDate != null
                ? getTranslation("finance.wealth-event.interval.range",
                        formatDate(previousDate), formatDate(targetDate))
                : getTranslation("finance.wealth-event.interval.single", formatDate(targetDate));

        Span rangeSpan = new Span(rangeText);
        rangeSpan.addClassNames(LumoUtility.FontWeight.SEMIBOLD);

        HorizontalLayout row = new HorizontalLayout(rangeSpan);
        row.setAlignItems(Alignment.CENTER);
        row.getStyle().set("flex-wrap", "wrap");

        if (previousDate != null && delta != null) {
            String sign = delta.signum() > 0 ? "+" : "";
            Span deltaSpan = new Span(sign + MoneyFormatter.format(delta, locale()));
            deltaSpan.getElement().getThemeList().add("badge");
            deltaSpan.getElement().getThemeList().add("pill");
            deltaSpan.getElement().getThemeList().add(delta.signum() >= 0 ? "success" : "error");
            row.add(deltaSpan);
        }
        contextBanner.add(row);
    }

    private void configureGrid() {
        grid.addColumn(ev -> formatDate(ev.getEventDate()))
                .setHeader(getTranslation("finance.wealth-event.column.date"))
                .setAutoWidth(true)
                .setFlexGrow(0);
        grid.addColumn(new ComponentRenderer<>(this::renderAmountBadge))
                .setHeader(getTranslation("finance.wealth-event.column.amount"))
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true)
                .setFlexGrow(0);
        grid.addColumn(WealthEvent::getDescription)
                .setHeader(getTranslation("finance.wealth-event.column.description"))
                .setAutoWidth(true)
                .setFlexGrow(1);
        grid.addColumn(new ComponentRenderer<>(this::renderLoanBadge))
                .setHeader(getTranslation("finance.wealth-event.column.loan"))
                .setAutoWidth(true)
                .setFlexGrow(0);
        if (canModify) {
            grid.addColumn(new ComponentRenderer<>(this::renderRowActions))
                    .setHeader(getTranslation("finance.grid.column.actions"))
                    .setAutoWidth(true)
                    .setFlexGrow(0);
        }
        grid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES);
        grid.setWidthFull();
        grid.setAllRowsVisible(true);
    }

    private Span renderAmountBadge(WealthEvent event) {
        BigDecimal amt = event.getAmount();
        String sign = amt != null && amt.signum() > 0 ? "+" : "";
        Span span = new Span(amt != null ? sign + MoneyFormatter.format(amt, locale()) : "-");
        span.getStyle().set("white-space", "nowrap").set("font-weight", "600");
        if (amt != null) {
            span.getStyle().set("color",
                    amt.signum() < 0 ? "var(--lumo-error-text-color)" : "var(--lumo-success-text-color)");
        }
        return span;
    }

    private Span renderLoanBadge(WealthEvent event) {
        if (event.getLoanContractId() == null) {
            return new Span("");
        }
        LoanContract loan = loansById.get(event.getLoanContractId());
        String label = loan != null
                ? loan.getDesignation()
                : getTranslation("finance.wealth-event.loan.linked-fallback");
        Span badge = new Span(label);
        badge.getElement().getThemeList().add("badge small contrast");
        return badge;
    }

    private HorizontalLayout renderRowActions(WealthEvent event) {
        Button deleteButton = GridActionButton.createDanger(
                VaadinIcon.TRASH,
                getTranslation("finance.wealth-event.action.delete"),
                click -> {
                    wealthEventService.delete(event.getId());
                    Notification.show(getTranslation("finance.wealth-event.notification.deleted"),
                            3000, Notification.Position.BOTTOM_START);
                    reloadDataAndPrefill();
                    if (onChanged != null) {
                        onChanged.run();
                    }
                });
        return new HorizontalLayout(deleteButton);
    }

    private void configureEditorSection() {
        editorSection.setPadding(true);
        editorSection.setSpacing(true);
        editorSection.setWidthFull();
        editorSection.addClassNames(LumoUtility.Background.CONTRAST_5, LumoUtility.BorderRadius.MEDIUM);

        modeSelector.setItems(EntryMode.EXPENSE, EntryMode.LOAN_LINK);
        modeSelector.setItemLabelGenerator(mode -> switch (mode) {
            case EXPENSE -> getTranslation("finance.wealth-event.mode.expense");
            case LOAN_LINK -> getTranslation("finance.wealth-event.mode.loan");
        });
        modeSelector.setValue(EntryMode.EXPENSE);
        modeSelector.addValueChangeListener(event -> updateModeVisibility());

        loanSelector.setLabel(getTranslation("finance.wealth-event.field.loan"));
        loanSelector.setItemLabelGenerator(this::formatLoanLabel);
        loanSelector.setWidth("420px");
        loanSelector.addValueChangeListener(event -> applySelectedLoan(event.getValue()));

        loanHintSpan.addClassNames(LumoUtility.FontSize.XSMALL, LumoUtility.TextColor.SUCCESS);
        HorizontalLayout loanRow = new HorizontalLayout(loanSelector, loanHintSpan);
        loanRow.setAlignItems(Alignment.END);
        loanRow.setWidthFull();
        loanRow.getStyle().set("flex-wrap", "wrap");

        dateField.setLabel(getTranslation("finance.wealth-event.field.date"));
        dateField.setWidth("160px");

        directionField.setLabel(getTranslation("finance.wealth-event.field.direction"));
        directionField.setItems(FlowDirection.OUTFLOW, FlowDirection.INFLOW);
        directionField.setItemLabelGenerator(dir -> switch (dir) {
            case OUTFLOW -> getTranslation("finance.wealth-event.direction.outflow");
            case INFLOW -> getTranslation("finance.wealth-event.direction.inflow");
        });
        directionField.setValue(FlowDirection.OUTFLOW);
        directionField.setWidth("200px");

        amountField.setLabel(getTranslation("finance.wealth-event.field.amount"));
        amountField.setWidth("160px");

        descriptionField.setLabel(getTranslation("finance.wealth-event.field.description"));
        descriptionField.setMaxLength(255);
        descriptionField.setWidthFull();

        saveButton.setText(getTranslation("finance.wealth-event.button.save"));
        saveButton.setIcon(VaadinIcon.PLUS.create());
        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveButton.addClickListener(event -> saveCurrentEntry());

        HorizontalLayout fieldsRow = new HorizontalLayout(
                dateField, directionField, amountField, descriptionField, saveButton);
        fieldsRow.setWidthFull();
        fieldsRow.setAlignItems(Alignment.END);
        fieldsRow.expand(descriptionField);
        fieldsRow.getStyle().set("flex-wrap", "wrap");

        editorSection.add(modeSelector, loanRow, fieldsRow);
    }

    private void updateModeVisibility() {
        boolean loanMode = modeSelector.getValue() == EntryMode.LOAN_LINK;
        loanSelector.setVisible(loanMode);
        loanHintSpan.setVisible(loanMode);
    }

    private void applySelectedLoan(LoanContract contract) {
        if (contract == null) {
            return;
        }
        if (contract.getStartDate() != null) {
            dateField.setValue(contract.getStartDate());
        }
        directionField.setValue(FlowDirection.OUTFLOW);
        if (contract.getOriginalAmount() != null) {
            amountField.setValue(contract.getOriginalAmount().abs());
        }
        descriptionField.setValue(getTranslation("finance.wealth-event.loan.default-desc", contract.getDesignation()));
    }

    private void saveCurrentEntry() {
        try {
            LocalDate date = dateField.getValue();
            BigDecimal rawAmount = amountField.getValue();
            if (date == null || rawAmount == null || rawAmount.signum() <= 0) {
                Notification.show(getTranslation("finance.wealth-event.error.invalid-input"),
                        4000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
                return;
            }
            BigDecimal signedAmount = directionField.getValue() == FlowDirection.INFLOW
                    ? rawAmount.abs()
                    : rawAmount.abs().negate();

            WealthEvent entity = new WealthEvent();
            entity.setHouseholdId(currentHouseholdId);
            entity.setEventDate(date);
            entity.setAmount(signedAmount);
            entity.setDescription(descriptionField.getValue());
            if (modeSelector.getValue() == EntryMode.LOAN_LINK && loanSelector.getValue() != null) {
                entity.setLoanContractId(loanSelector.getValue().getId());
            }

            wealthEventService.save(entity);
            Notification.show(getTranslation("finance.wealth-event.notification.saved"),
                    3000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            reloadDataAndPrefill();
            if (onChanged != null) {
                onChanged.run();
            }
        } catch (IllegalArgumentException exception) {
            Notification.show(exception.getMessage(), 5000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }

    static boolean isDateInInterval(LocalDate date, LocalDate prevExclusive, LocalDate targetInclusive) {
        if (date == null || targetInclusive == null) {
            return false;
        }
        if (date.isAfter(targetInclusive)) {
            return false;
        }
        return prevExclusive == null || date.isAfter(prevExclusive);
    }

    private String formatLoanLabel(LoanContract contract) {
        if (contract == null) {
            return "";
        }
        return contract.getDesignation() + " – "
                + MoneyFormatter.format(contract.getOriginalAmount(), locale())
                + " (" + formatDate(contract.getStartDate()) + ")";
    }

    private String formatDate(LocalDate date) {
        return date == null ? "-" : DateFormatter.format(date, locale());
    }

    private static Locale locale() {
        return UI.getCurrent() != null ? UI.getCurrent().getLocale() : Locale.GERMANY;
    }
}
