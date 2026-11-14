package de.in.mp.admin;

import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.in.mp.finance.CategoryRepository;
import de.in.mp.finance.ClassificationRule;
import de.in.mp.finance.ClassificationRuleRepository;
import de.in.mp.finance.HouseholdService;
import de.in.mp.loans.LoanContractRepository;

/**
 * Administration service for transaction classification rules.
 */
@Service
public class ClassificationRuleService {

    private final ClassificationRuleRepository classificationRuleRepository;
    private final CategoryRepository categoryRepository;
    private final LoanContractRepository loanContractRepository;
    private final HouseholdService householdService;

    public ClassificationRuleService(ClassificationRuleRepository classificationRuleRepository,
            CategoryRepository categoryRepository, LoanContractRepository loanContractRepository) {
        this(classificationRuleRepository, categoryRepository, loanContractRepository, null);
    }

    @Autowired
    public ClassificationRuleService(ClassificationRuleRepository classificationRuleRepository,
            CategoryRepository categoryRepository, LoanContractRepository loanContractRepository,
            @Autowired(required = false) HouseholdService householdService) {
        this.classificationRuleRepository = classificationRuleRepository;
        this.categoryRepository = categoryRepository;
        this.loanContractRepository = loanContractRepository;
        this.householdService = householdService;
    }

    @Transactional(readOnly = true)
    public List<ClassificationRule> findAll() {
        UUID householdId = householdService == null ? null : householdService.resolveEffectiveHouseholdId(null);
        return householdId == null
                ? classificationRuleRepository.findAllByOrderByPriorityAsc()
                : classificationRuleRepository.findByHouseholdIdOrderByPriorityAsc(householdId);
    }

    @Transactional
    public ClassificationRule save(ClassificationRule rule) {
        if (rule != null && rule.getHouseholdId() == null && householdService != null) {
            rule.setHouseholdId(householdService.resolveEffectiveHouseholdId(null));
        }
        validate(rule);
        return classificationRuleRepository.save(rule);
    }

    @Transactional
    public void delete(UUID id) {
        classificationRuleRepository.delete(requireRule(id));
    }

    private void validate(ClassificationRule rule) {
        if (rule == null) {
            throw new IllegalArgumentException("Rule must not be null");
        }
        if (rule.getTargetFlow() == null) {
            throw new IllegalArgumentException("Target flow is required");
        }
        if (!hasAnyMatchCondition(rule)) {
            throw new IllegalArgumentException("At least one match condition is required");
        }
        if (rule.getTargetCategoryId() != null && !categoryRepository.existsById(rule.getTargetCategoryId())) {
            throw new IllegalArgumentException("Target category not found");
        }
        if (rule.getTargetLoanId() != null && !loanContractRepository.existsById(rule.getTargetLoanId())) {
            throw new IllegalArgumentException("Target loan not found");
        }
    }

    private boolean hasAnyMatchCondition(ClassificationRule rule) {
        return isNotBlank(rule.getMatchIban())
                || isNotBlank(rule.getMatchCounterpartyRegex())
                || isNotBlank(rule.getMatchPurposeRegex());
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private ClassificationRule requireRule(UUID id) {
        return classificationRuleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Rule not found: " + id));
    }
}
