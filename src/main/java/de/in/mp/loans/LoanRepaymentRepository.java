package de.in.mp.loans;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for loan repayments.
 */
public interface LoanRepaymentRepository extends JpaRepository<LoanRepayment, UUID> {

    List<LoanRepayment> findByLoanContractIdOrderByRepaymentDateAsc(UUID loanContractId);

    List<LoanRepayment> findAllByOrderByRepaymentDateDesc();
}
