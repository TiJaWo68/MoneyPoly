package de.in.mp.loans;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain service for loan repayments: recording, moving (re-allocation) and
 * deleting repayments with overpayment and debtor-integrity checks.
 */
@Service
public class LoanRepaymentService {

    private final LoanRepaymentRepository loanRepaymentRepository;
    private final LoanContractRepository loanContractRepository;

    public LoanRepaymentService(LoanRepaymentRepository loanRepaymentRepository,
            LoanContractRepository loanContractRepository) {
        this.loanRepaymentRepository = loanRepaymentRepository;
        this.loanContractRepository = loanContractRepository;
    }

    @Transactional(readOnly = true)
    public List<LoanRepayment> findByContract(UUID contractId) {
        return loanRepaymentRepository.findByLoanContractIdOrderByRepaymentDateAsc(contractId);
    }

    @Transactional
    public LoanRepayment record(LoanRepayment repayment) {
        LoanContract contract = requireContract(repayment.getLoanContractId());
        validate(repayment, contract);
        checkOverpayment(contract, repayment.getPrincipalAmount());
        return loanRepaymentRepository.save(repayment);
    }

    @Transactional
    public LoanRepayment move(UUID repaymentId, UUID targetContractId) {
        LoanRepayment repayment = requireRepayment(repaymentId);
        LoanContract target = requireContract(targetContractId);

        if (repayment.getRepaymentDate().isBefore(target.getStartDate())) {
            throw new IllegalArgumentException("Repayment date is before the target contract start date");
        }
        if (!target.getDebtorIds().contains(repayment.getPayerId())) {
            throw new IllegalArgumentException("Payer is not a debtor of the target contract");
        }
        checkOverpayment(target, repayment.getPrincipalAmount());

        repayment.setLoanContractId(targetContractId);
        return loanRepaymentRepository.save(repayment);
    }

    @Transactional
    public void delete(UUID repaymentId) {
        loanRepaymentRepository.delete(requireRepayment(repaymentId));
    }

    private void checkOverpayment(LoanContract contract, BigDecimal additionalPrincipal) {
        BigDecimal existingPrincipal = loanRepaymentRepository
                .findByLoanContractIdOrderByRepaymentDateAsc(contract.getId()).stream()
                .map(LoanRepayment::getPrincipalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal newTotal = contract.getInitialRepaid().add(existingPrincipal).add(additionalPrincipal);

        if (newTotal.compareTo(contract.getOriginalAmount()) > 0) {
            throw new LoanOverpaymentException(newTotal.subtract(contract.getOriginalAmount()));
        }
    }

    private void validate(LoanRepayment repayment, LoanContract contract) {
        if (repayment == null) {
            throw new IllegalArgumentException("Repayment must not be null");
        }
        if (repayment.getLoanContractId() == null) {
            throw new IllegalArgumentException("Loan contract is required");
        }
        if (repayment.getPayerId() == null) {
            throw new IllegalArgumentException("Payer is required");
        }
        if (!contract.getDebtorIds().contains(repayment.getPayerId())) {
            throw new IllegalArgumentException("Payer is not a debtor of this contract");
        }
        if (repayment.getRepaymentDate() == null) {
            throw new IllegalArgumentException("Repayment date is required");
        }
        if (repayment.getRepaymentDate().isBefore(contract.getStartDate())) {
            throw new IllegalArgumentException("Repayment date is before the contract start date");
        }
        if (repayment.getPrincipalAmount() == null) {
            repayment.setPrincipalAmount(BigDecimal.ZERO);
        }
        if (repayment.getInterestAmount() == null) {
            repayment.setInterestAmount(BigDecimal.ZERO);
        }
        if (repayment.getPrincipalAmount().signum() < 0 || repayment.getInterestAmount().signum() < 0) {
            throw new IllegalArgumentException("Amounts must not be negative");
        }
        if (repayment.getPrincipalAmount().add(repayment.getInterestAmount()).signum() <= 0) {
            throw new IllegalArgumentException("Repayment amount must be greater than zero");
        }
        if (repayment.getPaymentMethod() == null) {
            throw new IllegalArgumentException("Payment method is required");
        }
    }

    private LoanContract requireContract(UUID id) {
        return loanContractRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Loan contract not found: " + id));
    }

    private LoanRepayment requireRepayment(UUID id) {
        return loanRepaymentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Repayment not found: " + id));
    }
}
