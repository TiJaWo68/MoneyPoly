package de.in.mp.ui.admin;

import java.util.List;
import java.util.UUID;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.binder.Binder;

import de.in.mp.admin.CategoryService;
import de.in.mp.admin.ClassificationRuleService;
import de.in.mp.finance.Category;
import de.in.mp.finance.ClassificationRule;
import de.in.mp.finance.FlowType;
import de.in.mp.loans.LoanContract;
import de.in.mp.loans.LoanContractRepository;

/**
 * Modal editor for creating and updating a transaction classification rule.
 */
public class ClassificationRuleEditorDialog extends Dialog {

    private final ClassificationRuleService classificationRuleService;
    private final CategoryService categoryService;
    private final LoanContractRepository loanContractRepository;

    private final Binder<ClassificationRule> binder = new Binder<>(ClassificationRule.class);

    private final IntegerField priority = new IntegerField();
    private final TextField matchIban = new TextField();
    private final TextField matchCounterpartyRegex = new TextField();
    private final TextField matchPurposeRegex = new TextField();
    private final ComboBox<FlowType> targetFlow = new ComboBox<>();
    private final ComboBox<Category> targetCategory = new ComboBox<>();
    private final ComboBox<LoanContract> targetLoan = new ComboBox<>();
    private final Checkbox flagForReview = new Checkbox();

    private final Button saveButton = new Button();
    private final Button cancelButton = new Button();

    private ClassificationRule currentRule;
    private Runnable onSaved;
    private List<Category> categories = List.of();
    private List<LoanContract> loans = List.of();

    public ClassificationRuleEditorDialog(ClassificationRuleService classificationRuleService,
            CategoryService categoryService, LoanContractRepository loanContractRepository) {
        this.classificationRuleService = classificationRuleService;
        this.categoryService = categoryService;
        this.loanContractRepository = loanContractRepository;

        configureFields();
        configureBinder();

        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveButton.addClickListener(event -> save());
        cancelButton.addClickListener(event -> close());

        FormLayout form = new FormLayout(priority, matchIban, matchCounterpartyRegex, matchPurposeRegex, targetFlow,
                targetCategory, targetLoan, flagForReview);
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1));

        HorizontalLayout actions = new HorizontalLayout(cancelButton, saveButton);
        actions.setWidthFull();
        actions.setJustifyContentMode(JustifyContentMode.END);

        add(form, actions);
        setCloseOnEsc(true);
        setCloseOnOutsideClick(false);
    }

    public void openForCreate(Runnable onSaved) {
        this.onSaved = onSaved;
        this.currentRule = new ClassificationRule();
        setHeaderTitle(getTranslation("admin.rule.editor.title.new"));
        binder.readBean(currentRule);
        refreshLookups();
        targetCategory.setValue(null);
        targetLoan.setValue(null);
        open();
    }

    public void openForEdit(ClassificationRule rule, Runnable onSaved) {
        this.onSaved = onSaved;
        this.currentRule = rule;
        setHeaderTitle(getTranslation("admin.rule.editor.title.edit"));
        binder.readBean(rule);
        refreshLookups();
        targetCategory.setValue(resolveCategory(rule.getTargetCategoryId()));
        targetLoan.setValue(resolveLoan(rule.getTargetLoanId()));
        open();
    }

    private void configureFields() {
        priority.setLabel(getTranslation("admin.rule.editor.field.priority"));
        matchIban.setLabel(getTranslation("admin.rule.editor.field.iban"));
        matchCounterpartyRegex.setLabel(getTranslation("admin.rule.editor.field.counterparty-regex"));
        matchPurposeRegex.setLabel(getTranslation("admin.rule.editor.field.purpose-regex"));
        targetFlow.setLabel(getTranslation("admin.rule.editor.field.target-flow"));
        targetCategory.setLabel(getTranslation("admin.rule.editor.field.target-category"));
        targetLoan.setLabel(getTranslation("admin.rule.editor.field.target-loan"));
        flagForReview.setLabel(getTranslation("admin.rule.editor.field.flag-for-review"));
        saveButton.setText(getTranslation("admin.rule.editor.button.save"));
        cancelButton.setText(getTranslation("admin.rule.editor.button.cancel"));

        priority.setValue(ClassificationRule.DEFAULT_PRIORITY);

        targetFlow.setItems(FlowType.values());
        targetFlow.setItemLabelGenerator(flow -> getTranslation("admin.flow." + flow.name()));

        targetCategory.setItemLabelGenerator(Category::getName);
        targetCategory.setClearButtonVisible(true);

        targetLoan.setItemLabelGenerator(loan -> loan.getDesignation() + " (" + loan.getOriginalAmount() + ")");
        targetLoan.setClearButtonVisible(true);
    }

    private void configureBinder() {
        binder.forField(priority)
                .bind(rule -> rule.getPriority(), (rule, value) -> rule.setPriority(value));

        binder.forField(matchIban)
                .bind(ClassificationRule::getMatchIban, ClassificationRule::setMatchIban);

        binder.forField(matchCounterpartyRegex)
                .bind(ClassificationRule::getMatchCounterpartyRegex, ClassificationRule::setMatchCounterpartyRegex);

        binder.forField(matchPurposeRegex)
                .bind(ClassificationRule::getMatchPurposeRegex, ClassificationRule::setMatchPurposeRegex);

        binder.forField(targetFlow)
                .asRequired(getTranslation("admin.rule.editor.error.target-flow-required"))
                .bind(ClassificationRule::getTargetFlow, ClassificationRule::setTargetFlow);

        binder.forField(flagForReview)
                .bind(ClassificationRule::isFlagForReview, ClassificationRule::setFlagForReview);
    }

    private void refreshLookups() {
        categories = categoryService.findAll();
        targetCategory.setItems(categories);

        loans = loanContractRepository.findByActiveTrueOrderByDesignationAsc();
        if (currentRule.getTargetLoanId() != null && resolveLoan(currentRule.getTargetLoanId()) == null) {
            loanContractRepository.findById(currentRule.getTargetLoanId())
                    .ifPresent(loan -> loans = List.of(loan));
        }
        targetLoan.setItems(loans);
    }

    private Category resolveCategory(UUID categoryId) {
        if (categoryId == null) {
            return null;
        }
        return categories.stream()
                .filter(category -> categoryId.equals(category.getId()))
                .findFirst()
                .orElse(null);
    }

    private LoanContract resolveLoan(UUID loanId) {
        if (loanId == null) {
            return null;
        }
        return loans.stream()
                .filter(loan -> loanId.equals(loan.getId()))
                .findFirst()
                .orElse(null);
    }

    private void save() {
        if (!binder.writeBeanIfValid(currentRule)) {
            return;
        }
        currentRule.setTargetCategoryId(targetCategory.getValue() == null ? null : targetCategory.getValue().getId());
        currentRule.setTargetLoanId(targetLoan.getValue() == null ? null : targetLoan.getValue().getId());

        try {
            classificationRuleService.save(currentRule);
            close();
            if (onSaved != null) {
                onSaved.run();
            }
        } catch (IllegalArgumentException exception) {
            Notification.show(getTranslation("admin.notification.error", exception.getMessage()), 5000,
                    Notification.Position.MIDDLE);
        }
    }
}
