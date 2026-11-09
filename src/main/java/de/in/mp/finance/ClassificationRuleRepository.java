package de.in.mp.finance;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for classification rules.
 */
public interface ClassificationRuleRepository extends JpaRepository<ClassificationRule, UUID> {

    List<ClassificationRule> findAllByOrderByPriorityAsc();

    long countByTargetCategoryId(UUID categoryId);

    long countByTargetLoanId(UUID loanId);
}
