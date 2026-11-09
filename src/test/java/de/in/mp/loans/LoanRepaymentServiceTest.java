package de.in.mp.loans;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoanRepaymentServiceTest {

    private LoanRepaymentRepository loanRepaymentRepository;
    private LoanContractRepository loanContractRepository;
    private LoanRepaymentService service;

    @BeforeEach
    void setUp() {
        loanRepaymentRepository = mock(LoanRepaymentRepository.class);
        loanContractRepository = mock(LoanContractRepository.class);
        service = new LoanRepaymentService(loanRepaymentRepository, loanContractRepository);
    }

    @Test
    void recordSavesValidRepayment() {
        UUID contractId = UUID.randomUUID();
        UUID payerId = UUID.randomUUID();
        LoanContract contract = contract(contractId, "10000.00", Set.of(payerId));
        when(loanContractRepository.findById(contractId)).thenReturn(Optional.of(contract));
        when(loanRepaymentRepository.findByLoanContractIdOrderByRepaymentDateAsc(contractId))
                .thenReturn(List.of());
        when(loanRepaymentRepository.save(any(LoanRepayment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        LoanRepayment repayment = repayment(contractId, payerId, "500.00");
        LoanRepayment saved = service.record(repayment);

        assertThat(saved.getLoanContractId()).isEqualTo(contractId);
        verify(loanRepaymentRepository).save(repayment);
    }

    @Test
    void recordRejectsOverpayment() {
        UUID contractId = UUID.randomUUID();
        UUID payerId = UUID.randomUUID();
        LoanContract contract = contract(contractId, "10000.00", Set.of(payerId));
        when(loanContractRepository.findById(contractId)).thenReturn(Optional.of(contract));

        LoanRepayment existing = new LoanRepayment();
        existing.setPrincipalAmount(new BigDecimal("9800.00"));
        when(loanRepaymentRepository.findByLoanContractIdOrderByRepaymentDateAsc(contractId))
                .thenReturn(List.of(existing));

        LoanRepayment repayment = repayment(contractId, payerId, "500.00");

        assertThatThrownBy(() -> service.record(repayment))
                .isInstanceOf(LoanOverpaymentException.class);
    }

    @Test
    void recordRejectsNonDebtorPayer() {
        UUID contractId = UUID.randomUUID();
        LoanContract contract = contract(contractId, "10000.00", Set.of(UUID.randomUUID()));
        when(loanContractRepository.findById(contractId)).thenReturn(Optional.of(contract));

        LoanRepayment repayment = repayment(contractId, UUID.randomUUID(), "500.00");

        assertThatThrownBy(() -> service.record(repayment))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a debtor");
    }

    @Test
    void recordRejectsDateBeforeContractStart() {
        UUID contractId = UUID.randomUUID();
        UUID payerId = UUID.randomUUID();
        LoanContract contract = contract(contractId, "10000.00", Set.of(payerId));
        when(loanContractRepository.findById(contractId)).thenReturn(Optional.of(contract));

        LoanRepayment repayment = repayment(contractId, payerId, "500.00");
        repayment.setRepaymentDate(LocalDate.of(2023, 12, 31));

        assertThatThrownBy(() -> service.record(repayment))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("start date");
    }

    @Test
    void moveRejectsDateBeforeTargetStart() {
        UUID repaymentId = UUID.randomUUID();
        UUID targetContractId = UUID.randomUUID();

        LoanRepayment repayment = repayment(UUID.randomUUID(), UUID.randomUUID(), "500.00");
        repayment.setId(repaymentId);
        repayment.setRepaymentDate(LocalDate.of(2024, 1, 1));
        when(loanRepaymentRepository.findById(repaymentId)).thenReturn(Optional.of(repayment));

        LoanContract target = contract(targetContractId, "10000.00", Set.of(repayment.getPayerId()));
        target.setStartDate(LocalDate.of(2024, 2, 1));
        when(loanContractRepository.findById(targetContractId)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service.move(repaymentId, targetContractId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target contract start date");
    }

    @Test
    void moveReassignsRepaymentToTargetContract() {
        UUID repaymentId = UUID.randomUUID();
        UUID targetContractId = UUID.randomUUID();
        UUID payerId = UUID.randomUUID();

        LoanRepayment repayment = repayment(UUID.randomUUID(), payerId, "500.00");
        repayment.setId(repaymentId);
        when(loanRepaymentRepository.findById(repaymentId)).thenReturn(Optional.of(repayment));

        LoanContract target = contract(targetContractId, "10000.00", Set.of(payerId));
        when(loanContractRepository.findById(targetContractId)).thenReturn(Optional.of(target));
        when(loanRepaymentRepository.findByLoanContractIdOrderByRepaymentDateAsc(targetContractId))
                .thenReturn(List.of());
        when(loanRepaymentRepository.save(any(LoanRepayment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        LoanRepayment moved = service.move(repaymentId, targetContractId);

        assertThat(moved.getLoanContractId()).isEqualTo(targetContractId);
        verify(loanRepaymentRepository).save(repayment);
    }

    @Test
    void deleteRemovesRepayment() {
        UUID repaymentId = UUID.randomUUID();
        LoanRepayment repayment = repayment(UUID.randomUUID(), UUID.randomUUID(), "500.00");
        when(loanRepaymentRepository.findById(repaymentId)).thenReturn(Optional.of(repayment));

        service.delete(repaymentId);

        verify(loanRepaymentRepository).delete(repayment);
    }

    private LoanContract contract(UUID id, String originalAmount, Set<UUID> debtorIds) {
        LoanContract contract = new LoanContract();
        contract.setId(id);
        contract.setDesignation("Test");
        contract.setOriginalAmount(new BigDecimal(originalAmount));
        contract.setStartDate(LocalDate.of(2024, 1, 1));
        contract.setDebtorIds(debtorIds);
        return contract;
    }

    private LoanRepayment repayment(UUID contractId, UUID payerId, String principalAmount) {
        LoanRepayment repayment = new LoanRepayment();
        repayment.setLoanContractId(contractId);
        repayment.setPayerId(payerId);
        repayment.setRepaymentDate(LocalDate.of(2024, 3, 1));
        repayment.setPrincipalAmount(new BigDecimal(principalAmount));
        repayment.setInterestAmount(BigDecimal.ZERO);
        repayment.setPaymentMethod(PaymentMethod.BANK_TRANSFER);
        return repayment;
    }
}
