package de.in.mp.admin;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.in.mp.finance.CategoryRepository;
import de.in.mp.finance.ClassificationRule;
import de.in.mp.finance.ClassificationRuleRepository;
import de.in.mp.finance.FlowType;
import de.in.mp.loans.LoanContractRepository;

class ClassificationRuleServiceTest {

    private ClassificationRuleRepository classificationRuleRepository;
    private CategoryRepository categoryRepository;
    private LoanContractRepository loanContractRepository;
    private ClassificationRuleService service;

    @BeforeEach
    void setUp() {
        classificationRuleRepository = mock(ClassificationRuleRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        loanContractRepository = mock(LoanContractRepository.class);
        service = new ClassificationRuleService(classificationRuleRepository, categoryRepository,
                loanContractRepository);
    }

    @Test
    void saveRejectsMissingTargetFlow() {
        ClassificationRule rule = new ClassificationRule();
        rule.setMatchIban("DE02120300000000202051");

        assertThatThrownBy(() -> service.save(rule))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flow");
    }

    @Test
    void saveRejectsMissingMatchCondition() {
        ClassificationRule rule = new ClassificationRule();
        rule.setTargetFlow(FlowType.EXPENSE);

        assertThatThrownBy(() -> service.save(rule))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("match");
    }

    @Test
    void saveRejectsUnknownTargetCategory() {
        UUID categoryId = UUID.randomUUID();
        when(categoryRepository.existsById(categoryId)).thenReturn(false);

        ClassificationRule rule = rule(FlowType.EXPENSE);
        rule.setTargetCategoryId(categoryId);

        assertThatThrownBy(() -> service.save(rule))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("category");

        verify(classificationRuleRepository, never()).save(any(ClassificationRule.class));
    }

    @Test
    void saveRejectsUnknownTargetLoan() {
        UUID loanId = UUID.randomUUID();
        when(loanContractRepository.existsById(loanId)).thenReturn(false);

        ClassificationRule rule = rule(FlowType.EXPENSE);
        rule.setTargetLoanId(loanId);

        assertThatThrownBy(() -> service.save(rule))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loan");

        verify(classificationRuleRepository, never()).save(any(ClassificationRule.class));
    }

    @Test
    void savePersistsValidRule() {
        ClassificationRule rule = rule(FlowType.EXPENSE);
        when(classificationRuleRepository.save(rule)).thenReturn(rule);

        service.save(rule);

        verify(classificationRuleRepository).save(rule);
    }

    private ClassificationRule rule(FlowType targetFlow) {
        ClassificationRule rule = new ClassificationRule();
        rule.setTargetFlow(targetFlow);
        rule.setMatchPurposeRegex("REWE");
        return rule;
    }
}
