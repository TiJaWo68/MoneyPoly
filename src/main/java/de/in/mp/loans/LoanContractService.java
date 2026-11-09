package de.in.mp.loans;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain service for private loan contracts: validation, balance and
 * status calculation plus dashboard KPIs.
 */
@Service
public class LoanContractService {

    private static final int DESIGNATION_MAX_LENGTH = 80;

    private final LoanContractRepository loanContractRepository;
    private final LoanRepaymentRepository loanRepaymentRepository;

    public LoanContractService(LoanContractRepository loanContractRepository,
            LoanRepaymentRepository loanRepaymentRepository) {
        this.loanContractRepository = loanContractRepository;
        this.loanRepaymentRepository = loanRepaymentRepository;
    }

    @Transactional(readOnly = true)
    public List<LoanContract> findAll() {
        return loanContractRepository.findAllByOrderByDesignationAsc();
    }

    @Transactional(readOnly = true)
    public LoanContract requireContract(UUID id) {
        return loanContractRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Loan contract not found: " + id));
    }

    @Transactional
    public LoanContract save(LoanContract contract) {
        validate(contract);
        return loanContractRepository.save(contract);
    }

    @Transactional(readOnly = true)
    public BigDecimal totalRepaid(UUID contractId) {
        return totalRepaid(requireContract(contractId));
    }

    @Transactional(readOnly = true)
    public BigDecimal totalRepaid(LoanContract contract) {
        BigDecimal principalSum = loanRepaymentRepository
                .findByLoanContractIdOrderByRepaymentDateAsc(contract.getId()).stream()
                .map(LoanRepayment::getPrincipalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return contract.getInitialRepaid().add(principalSum);
    }

    @Transactional(readOnly = true)
    public BigDecimal remainingBalance(LoanContract contract) {
        return contract.getOriginalAmount().subtract(totalRepaid(contract));
    }

    @Transactional(readOnly = true)
    public boolean isPaidOff(LoanContract contract) {
        return remainingBalance(contract).signum() <= 0;
    }

    @Transactional(readOnly = true)
    public BigDecimal calculateTotalLentCapital() {
        return findAll().stream()
                .map(LoanContract::getOriginalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Transactional(readOnly = true)
    public BigDecimal calculateTotalRepaid() {
        return findAll().stream()
                .map(this::totalRepaid)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Transactional(readOnly = true)
    public BigDecimal calculateTotalOutstanding() {
        return findAll().stream()
                .filter(contract -> !isPaidOff(contract))
                .map(this::remainingBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Outstanding receivables of active contracts only (open loan claims).
     */
    @Transactional(readOnly = true)
    public BigDecimal calculateActiveOutstanding() {
        return findAll().stream()
                .filter(LoanContract::isActive)
                .filter(contract -> !isPaidOff(contract))
                .map(this::remainingBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Transactional(readOnly = true)
    public long countActiveContracts() {
        return findAll().stream()
                .filter(contract -> contract.isActive() && !isPaidOff(contract))
                .count();
    }

    private void validate(LoanContract contract) {
        if (contract == null) {
            throw new IllegalArgumentException("Loan contract must not be null");
        }
        if (contract.getDesignation() == null || contract.getDesignation().isBlank()) {
            throw new IllegalArgumentException("Designation is required");
        }
        String designation = contract.getDesignation().trim();
        if (designation.length() > DESIGNATION_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "Designation must not exceed " + DESIGNATION_MAX_LENGTH + " characters");
        }
        contract.setDesignation(designation);

        if (contract.getOriginalAmount() == null || contract.getOriginalAmount().signum() <= 0) {
            throw new IllegalArgumentException("Original amount must be greater than zero");
        }
        if (contract.getStartDate() == null) {
            throw new IllegalArgumentException("Start date is required");
        }
        if (contract.getDebtorIds() == null || contract.getDebtorIds().isEmpty()) {
            throw new IllegalArgumentException("At least one debtor is required");
        }

        if (contract.getInitialRepaid() == null) {
            contract.setInitialRepaid(BigDecimal.ZERO);
        }
        if (contract.getInitialRepaid().signum() < 0) {
            throw new IllegalArgumentException("Initial repaid amount must not be negative");
        }
        if (contract.getInitialRepaid().compareTo(contract.getOriginalAmount()) > 0) {
            throw new IllegalArgumentException("Initial repaid amount must not exceed the original amount");
        }

        if (contract.getAnnualInterestRate() == null) {
            contract.setAnnualInterestRate(BigDecimal.ZERO);
        }
        if (contract.getAnnualInterestRate().signum() < 0) {
            throw new IllegalArgumentException("Annual interest rate must not be negative");
        }
    }
}
