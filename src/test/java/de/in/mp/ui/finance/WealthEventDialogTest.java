package de.in.mp.ui.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.UI;

import de.in.mp.finance.WealthEvent;
import de.in.mp.finance.WealthEventService;
import de.in.mp.loans.LoanContract;
import de.in.mp.loans.LoanContractService;

class WealthEventDialogTest {

    @BeforeEach
    void setUp() {
        UI.setCurrent(new UI());
    }

    @AfterEach
    void tearDown() {
        UI.setCurrent(null);
    }

    @Test
    void isDateInIntervalUsesExclusivePreviousAndInclusiveTargetBounds() {
        LocalDate prev = LocalDate.of(2025, 1, 31);
        LocalDate target = LocalDate.of(2025, 2, 28);

        assertThat(WealthEventDialog.isDateInInterval(prev, prev, target)).isFalse();
        assertThat(WealthEventDialog.isDateInInterval(LocalDate.of(2025, 2, 1), prev, target)).isTrue();
        assertThat(WealthEventDialog.isDateInInterval(target, prev, target)).isTrue();
        assertThat(WealthEventDialog.isDateInInterval(LocalDate.of(2025, 3, 1), prev, target)).isFalse();
        assertThat(WealthEventDialog.isDateInInterval(prev, null, target)).isTrue();
    }

    @Test
    void findsUnlinkedLoanInIntervalOrToleranceAndSkipsAlreadyLinkedOnes() {
        UUID linkedLoanId = UUID.randomUUID();
        UUID unlinkedLoanId = UUID.randomUUID();
        UUID nearbyLoanId = UUID.randomUUID();

        LoanContract linkedLoan = loan(linkedLoanId, "Loan Already Linked", LocalDate.of(2025, 2, 10), "5000.00");
        LoanContract unlinkedLoan = loan(unlinkedLoanId, "Private Loan Alpha", LocalDate.of(2025, 2, 15), "12000.00");
        LoanContract nearbyLoan = loan(nearbyLoanId, "Private Loan Nearby", LocalDate.of(2025, 3, 2), "3000.00");

        WealthEvent linkedEvent = new WealthEvent();
        linkedEvent.setId(UUID.randomUUID());
        linkedEvent.setEventDate(LocalDate.of(2025, 2, 10));
        linkedEvent.setAmount(new BigDecimal("-5000.00"));
        linkedEvent.setDescription("Already linked");
        linkedEvent.setLoanContractId(linkedLoanId);

        LocalDate prev = LocalDate.of(2025, 1, 31);
        LocalDate target = LocalDate.of(2025, 2, 28);

        // Exact interval match wins first and skips linkedLoan
        LoanContract matched = WealthEventDialog.findUnlinkedLoanInInterval(
                prev, target, List.of(linkedLoan, unlinkedLoan, nearbyLoan), List.of(linkedEvent));
        assertThat(matched).isNotNull();
        assertThat(matched.getId()).isEqualTo(unlinkedLoanId);

        // When no exact interval match exists, fallback finds nearby loan within +/- 3 days
        LoanContract nearbyMatched = WealthEventDialog.findUnlinkedLoanInInterval(
                prev, target, List.of(linkedLoan, nearbyLoan), List.of(linkedEvent));
        assertThat(nearbyMatched).isNotNull();
        assertThat(nearbyMatched.getId()).isEqualTo(nearbyLoanId);

        // Outside +/- 3 days returns null
        LoanContract distantLoan = loan(UUID.randomUUID(), "Older Loan", LocalDate.of(2024, 10, 1), "1000.00");
        assertThat(WealthEventDialog.findUnlinkedLoanInInterval(
                prev, target, List.of(distantLoan), List.of())).isNull();
    }

    @Test
    void opensForIntervalWithMockedServicesAndDoesNotFailOnNullLoans() {
        WealthEventService eventService = mock(WealthEventService.class);
        LoanContractService loanService = mock(LoanContractService.class);
        UUID householdId = UUID.randomUUID();

        WealthEvent ev = new WealthEvent();
        ev.setId(UUID.randomUUID());
        ev.setHouseholdId(householdId);
        ev.setEventDate(LocalDate.of(2025, 2, 12));
        ev.setAmount(new BigDecimal("-8000.00"));
        ev.setDescription("Sample major expense");

        when(eventService.findByHousehold(householdId)).thenReturn(List.of(ev));
        when(loanService.findByHousehold(householdId)).thenReturn(List.of());

        WealthEventDialog dialog = new WealthEventDialog(eventService, loanService, true);
        dialog.openForInterval(
                householdId,
                LocalDate.of(2025, 1, 31),
                LocalDate.of(2025, 2, 28),
                new BigDecimal("-6500.00"),
                () -> {
                });

        assertThat(dialog.isOpened()).isTrue();
    }

    private static LoanContract loan(UUID id, String name, LocalDate startDate, String amount) {
        LoanContract contract = new LoanContract();
        contract.setId(id);
        contract.setDesignation(name);
        contract.setStartDate(startDate);
        contract.setOriginalAmount(new BigDecimal(amount));
        contract.setActive(true);
        return contract;
    }
}
