package de.in.mp.loans;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoanContractServiceTest {

    private LoanContractRepository loanContractRepository;
    private LoanRepaymentRepository loanRepaymentRepository;
    private LoanContractService service;

    @BeforeEach
    void setUp() {
        loanContractRepository = mock(LoanContractRepository.class);
        loanRepaymentRepository = mock(LoanRepaymentRepository.class);
        service = new LoanContractService(loanContractRepository, loanRepaymentRepository);
    }

    @Test
    void saveRejectsBlankDesignation() {
        LoanContract contract = contract("   ", "10000.00", Set.of(UUID.randomUUID()));

        assertThatThrownBy(() -> service.save(contract))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Designation");
    }

    @Test
    void saveRejectsNonPositiveOriginalAmount() {
        LoanContract contract = contract("Auto", "0.00", Set.of(UUID.randomUUID()));

        assertThatThrownBy(() -> service.save(contract))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Original amount");
    }

    @Test
    void saveRejectsMissingDebtor() {
        LoanContract contract = contract("Auto", "10000.00", Set.of());

        assertThatThrownBy(() -> service.save(contract))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("debtor");
    }

    @Test
    void saveRejectsInitialRepaidExceedingOriginalAmount() {
        LoanContract contract = contract("Auto", "10000.00", Set.of(UUID.randomUUID()));
        contract.setInitialRepaid(new BigDecimal("12000.00"));

        assertThatThrownBy(() -> service.save(contract))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Initial repaid");
    }

    @Test
    void totalRepaidSumsInitialRepaidAndPrincipalRepayments() {
        UUID contractId = UUID.randomUUID();
        LoanContract contract = contract("Auto", "10000.00", Set.of(UUID.randomUUID()));
        contract.setId(contractId);
        contract.setInitialRepaid(new BigDecimal("1000.00"));

        LoanRepayment repayment = new LoanRepayment();
        repayment.setPrincipalAmount(new BigDecimal("2000.00"));
        when(loanRepaymentRepository.findByLoanContractIdOrderByRepaymentDateAsc(contractId))
                .thenReturn(List.of(repayment));

        assertThat(service.totalRepaid(contract)).isEqualByComparingTo("3000.00");
    }

    @Test
    void remainingBalanceSubtractsRepaidFromOriginalAmount() {
        UUID contractId = UUID.randomUUID();
        LoanContract contract = contract("Auto", "10000.00", Set.of(UUID.randomUUID()));
        contract.setId(contractId);
        contract.setInitialRepaid(new BigDecimal("1000.00"));

        when(loanRepaymentRepository.findByLoanContractIdOrderByRepaymentDateAsc(contractId))
                .thenReturn(List.of());

        assertThat(service.remainingBalance(contract)).isEqualByComparingTo("9000.00");
    }

    @Test
    void isPaidOffWhenRemainingBalanceIsZero() {
        UUID contractId = UUID.randomUUID();
        LoanContract contract = contract("Auto", "1000.00", Set.of(UUID.randomUUID()));
        contract.setId(contractId);
        contract.setInitialRepaid(new BigDecimal("1000.00"));

        when(loanRepaymentRepository.findByLoanContractIdOrderByRepaymentDateAsc(contractId))
                .thenReturn(List.of());

        assertThat(service.isPaidOff(contract)).isTrue();
    }

    @Test
    void calculateTotalLentCapitalSumsOriginalAmounts() {
        when(loanContractRepository.findAllByOrderByDesignationAsc()).thenReturn(List.of(
                contract("Auto", "10000.00", Set.of(UUID.randomUUID())),
                contract("Haus", "50000.00", Set.of(UUID.randomUUID()))));

        assertThat(service.calculateTotalLentCapital()).isEqualByComparingTo("60000.00");
    }

    @Test
    void calculateActiveOutstandingExcludesInactiveAndPaidOffContracts() {
        UUID activeId = UUID.randomUUID();
        LoanContract active = contract("Auto", "10000.00", Set.of(UUID.randomUUID()));
        active.setId(activeId);
        active.setInitialRepaid(new BigDecimal("2000.00"));

        LoanContract inactive = contract("Haus", "50000.00", Set.of(UUID.randomUUID()));
        inactive.setActive(false);

        when(loanContractRepository.findAllByOrderByDesignationAsc()).thenReturn(List.of(active, inactive));
        when(loanRepaymentRepository.findByLoanContractIdOrderByRepaymentDateAsc(activeId))
                .thenReturn(List.of());

        assertThat(service.calculateActiveOutstanding()).isEqualByComparingTo("8000.00");
    }

    @Test
    void countActiveContractsExcludesPaidOffContracts() {
        UUID paidOffId = UUID.randomUUID();
        LoanContract paidOff = contract("Auto", "1000.00", Set.of(UUID.randomUUID()));
        paidOff.setId(paidOffId);
        paidOff.setInitialRepaid(new BigDecimal("1000.00"));

        LoanContract active = contract("Haus", "50000.00", Set.of(UUID.randomUUID()));

        when(loanContractRepository.findAllByOrderByDesignationAsc()).thenReturn(List.of(paidOff, active));
        when(loanRepaymentRepository.findByLoanContractIdOrderByRepaymentDateAsc(paidOffId))
                .thenReturn(List.of());
        when(loanRepaymentRepository.findByLoanContractIdOrderByRepaymentDateAsc(active.getId()))
                .thenReturn(List.of());

        assertThat(service.countActiveContracts()).isEqualTo(1);
    }

    private LoanContract contract(String designation, String originalAmount, Set<UUID> debtorIds) {
        LoanContract contract = new LoanContract();
        contract.setDesignation(designation);
        contract.setOriginalAmount(new BigDecimal(originalAmount));
        contract.setStartDate(LocalDate.of(2024, 1, 1));
        contract.setDebtorIds(debtorIds);
        return contract;
    }
}
