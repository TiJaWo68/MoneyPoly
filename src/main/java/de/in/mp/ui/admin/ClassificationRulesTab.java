package de.in.mp.ui.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.renderer.ComponentRenderer;

import de.in.mp.admin.CategoryService;
import de.in.mp.admin.ClassificationRuleService;
import de.in.mp.finance.ClassificationRule;
import de.in.mp.loans.LoanContractRepository;
import de.in.mp.ui.GridActionButton;

/**
 * Tab 4: transaction classification rules (classification_rule).
 */
public class ClassificationRulesTab extends VerticalLayout implements AdminTab {

    private final ClassificationRuleService classificationRuleService;
    private final CategoryService categoryService;
    private final LoanContractRepository loanContractRepository;
    private final ClassificationRuleEditorDialog editorDialog;

    private final Grid<ClassificationRule> grid = new Grid<>(ClassificationRule.class, false);

    private Map<UUID, String> categoryNames = Map.of();
    private Map<UUID, String> loanDesignations = Map.of();

    public ClassificationRulesTab(ClassificationRuleService classificationRuleService,
            CategoryService categoryService, LoanContractRepository loanContractRepository) {
        this.classificationRuleService = classificationRuleService;
        this.categoryService = categoryService;
        this.loanContractRepository = loanContractRepository;
        this.editorDialog = new ClassificationRuleEditorDialog(classificationRuleService, categoryService,
                loanContractRepository);

        setSizeFull();
        setPadding(false);
        setSpacing(false);

        add(createHeader(), grid);
        configureGrid();
    }

    @Override
    public String getLabelKey() {
        return "admin.tab.rules";
    }

    @Override
    public Component asComponent() {
        return this;
    }

    @Override
    public void refresh() {
        categoryNames = categoryService.findAll().stream()
                .collect(Collectors.toMap(category -> category.getId(), category -> category.getName()));
        loanDesignations = loanContractRepository.findAllByOrderByDesignationAsc().stream()
                .collect(Collectors.toMap(loan -> loan.getId(), loan -> loan.getDesignation()));
        grid.setItems(classificationRuleService.findAll());
    }

    private HorizontalLayout createHeader() {
        Span title = new Span(getTranslation("admin.rules.title"));
        title.addClassName("admin-tab-title");

        Button newRuleButton = new Button(
                getTranslation("admin.rules.button.new-rule"),
                VaadinIcon.PLUS.create());
        newRuleButton.setTooltipText(getTranslation("admin.rules.button.new-rule"));
        newRuleButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        newRuleButton.addClickListener(event -> editorDialog.openForCreate(this::refresh));

        HorizontalLayout header = new HorizontalLayout(title, newRuleButton);
        header.setWidthFull();
        header.setDefaultVerticalComponentAlignment(Alignment.CENTER);
        header.expand(title);
        return header;
    }

    private void configureGrid() {
        grid.addColumn(ClassificationRule::getPriority)
                .setHeader(getTranslation("admin.grid.column.priority"))
                .setAutoWidth(true);

        grid.addColumn(this::renderMatchCriteria)
                .setHeader(getTranslation("admin.grid.column.match-criteria"))
                .setAutoWidth(true);

        grid.addColumn(new ComponentRenderer<>(this::renderTargetFlow))
                .setHeader(getTranslation("admin.grid.column.target-flow"))
                .setAutoWidth(true);

        grid.addColumn(rule -> categoryNames.getOrDefault(rule.getTargetCategoryId(), ""))
                .setHeader(getTranslation("admin.grid.column.target-category"))
                .setAutoWidth(true);

        grid.addColumn(rule -> loanDesignations.getOrDefault(rule.getTargetLoanId(), ""))
                .setHeader(getTranslation("admin.grid.column.target-loan"))
                .setAutoWidth(true);

        grid.addColumn(new ComponentRenderer<>(this::renderReviewFlag))
                .setHeader(getTranslation("admin.grid.column.flag-for-review"))
                .setAutoWidth(true);

        grid.addColumn(new ComponentRenderer<>(this::renderActions))
                .setHeader(getTranslation("admin.grid.column.actions"))
                .setAutoWidth(true);

        grid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_COLUMN_BORDERS);
    }

    private String renderMatchCriteria(ClassificationRule rule) {
        List<String> parts = new ArrayList<>();
        if (rule.getMatchIban() != null && !rule.getMatchIban().isBlank()) {
            parts.add(rule.getMatchIban());
        }
        if (rule.getMatchCounterpartyRegex() != null && !rule.getMatchCounterpartyRegex().isBlank()) {
            parts.add(rule.getMatchCounterpartyRegex());
        }
        if (rule.getMatchPurposeRegex() != null && !rule.getMatchPurposeRegex().isBlank()) {
            parts.add(rule.getMatchPurposeRegex());
        }
        return String.join(" | ", parts);
    }

    private Span renderTargetFlow(ClassificationRule rule) {
        Span badge = new Span(getTranslation("admin.flow." + rule.getTargetFlow().name()));
        badge.getElement().getThemeList().add("badge");
        badge.getElement().getThemeList().add("pill");
        badge.getElement().getThemeList().add("primary");
        return badge;
    }

    private Span renderReviewFlag(ClassificationRule rule) {
        String key = rule.isFlagForReview() ? "admin.rule.review.yes" : "admin.rule.review.no";
        Span badge = new Span(getTranslation(key));
        badge.getElement().getThemeList().add("badge");
        badge.getElement().getThemeList().add("pill");
        badge.getElement().getThemeList().add(rule.isFlagForReview() ? "warning" : "contrast");
        return badge;
    }

    private HorizontalLayout renderActions(ClassificationRule rule) {
        Button editButton = GridActionButton.create(
                VaadinIcon.EDIT,
                getTranslation("admin.action.edit"),
                event -> editorDialog.openForEdit(rule, this::refresh));

        Button deleteButton = GridActionButton.createDanger(
                VaadinIcon.TRASH,
                getTranslation("admin.action.delete"),
                event -> confirmDelete(rule));

        return new HorizontalLayout(editButton, deleteButton);
    }

    private void confirmDelete(ClassificationRule rule) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(getTranslation("admin.rule.confirm.delete.title"));

        VerticalLayout content = new VerticalLayout(
                new Span(getTranslation("admin.rule.confirm.delete.text", rule.getPriority())));

        Button cancelButton = new Button(getTranslation("admin.button.cancel"), event -> dialog.close());
        Button deleteButton = new Button(getTranslation("admin.action.delete"), event -> {
            dialog.close();
            deleteRule(rule);
        });
        deleteButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR);

        HorizontalLayout actions = new HorizontalLayout(cancelButton, deleteButton);
        actions.setWidthFull();
        actions.setJustifyContentMode(JustifyContentMode.END);

        dialog.add(content, actions);
        dialog.open();
    }

    private void deleteRule(ClassificationRule rule) {
        try {
            classificationRuleService.delete(rule.getId());
            Notification.show(getTranslation("admin.notification.deleted"), 3000, Notification.Position.MIDDLE);
            refresh();
        } catch (IllegalArgumentException exception) {
            Notification.show(getTranslation("admin.notification.error", exception.getMessage()), 5000,
                    Notification.Position.MIDDLE);
        }
    }
}
