package de.in.mp.ui.loans;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.in.mp.admin.PersonService;
import de.in.mp.finance.HouseholdService;
import de.in.mp.loans.LoanContract;
import de.in.mp.loans.LoanContractService;
import de.in.mp.loans.LoanRepaymentService;

class PrivateLoanViewTest {

    @Test
    void borrowerOnlyUserSeesOwnContractsWithoutWriteControls() {
        LoanContractService contractService = mock(LoanContractService.class);
        LoanRepaymentService repaymentService = mock(LoanRepaymentService.class);
        PersonService personService = mock(PersonService.class);
        HouseholdService householdService = mock(HouseholdService.class);

        UUID borrowerId = UUID.randomUUID();
        LoanContract ownContract = new LoanContract();
        ownContract.setId(UUID.randomUUID());
        ownContract.setDesignation("Eigenes Auto-Darlehen");
        ownContract.setOriginalAmount(new BigDecimal("5000.00"));
        ownContract.setInitialRepaid(new BigDecimal("1000.00"));
        ownContract.setStartDate(LocalDate.of(2025, 1, 1));
        ownContract.setDebtorIds(Set.of(borrowerId));
        ownContract.setActive(true);

        when(householdService.hasLenderLoanAccessForCurrentUser()).thenReturn(false);
        when(householdService.hasBorrowerLoanAccessForCurrentUser()).thenReturn(true);
        when(householdService.getCurrentPersonId()).thenReturn(Optional.of(borrowerId));
        when(contractService.findByBorrower(borrowerId)).thenReturn(List.of(ownContract));
        when(contractService.calculateTotalLentCapital(List.of(ownContract))).thenReturn(new BigDecimal("5000.00"));
        when(contractService.calculateTotalRepaid(List.of(ownContract))).thenReturn(new BigDecimal("1000.00"));
        when(contractService.calculateTotalOutstanding(List.of(ownContract))).thenReturn(new BigDecimal("4000.00"));

        PrivateLoanView view = new PrivateLoanView(contractService, repaymentService, personService, householdService);

        verify(contractService).findByBorrower(borrowerId);
        verify(contractService, never()).findAll();
        verify(personService, never()).findAll();
        assertThat(view.getChildren().count()).isGreaterThan(0);
    }
}
