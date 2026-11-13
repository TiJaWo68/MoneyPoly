package de.in.mp.ui.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.UI;

import de.in.mp.finance.Account;
import de.in.mp.finance.AccountService;
import de.in.mp.finance.AccountSnapshot;
import de.in.mp.finance.AccountSnapshotService;
import de.in.mp.finance.AccountType;
import de.in.mp.finance.BalanceDateRow;

class BalanceEntryDialogTest {

    @BeforeEach
    void setUp() {
        UI.setCurrent(new UI());
    }

    @AfterEach
    void tearDown() {
        UI.setCurrent(null);
    }

    @Test
    void showsOptionalInvestedCapitalFieldOnlyForInvestmentAccounts() {
        AccountService accountService = mock(AccountService.class);
        AccountSnapshotService snapshotService = mock(AccountSnapshotService.class);

        Account checking = new Account();
        checking.setId(UUID.randomUUID());
        checking.setName("Giro");
        checking.setAccountType(AccountType.CHECKING);
        checking.setActive(true);

        Account depot = new Account();
        depot.setId(UUID.randomUUID());
        depot.setName("Depot");
        depot.setAccountType(AccountType.INVESTMENT);
        depot.setActive(true);

        LocalDate refDate = LocalDate.of(2025, 1, 31);
        AccountSnapshot depotSnap = new AccountSnapshot();
        depotSnap.setAccountId(depot.getId());
        depotSnap.setSnapshotDate(refDate);
        depotSnap.setBalance(new BigDecimal("52210.00"));
        depotSnap.setInvestedCapital(new BigDecimal("48000.00"));

        when(accountService.findAll()).thenReturn(List.of(checking, depot));
        when(snapshotService.buildDateRows()).thenReturn(List.of());

        BalanceEntryDialog dialog = new BalanceEntryDialog(accountService, snapshotService);
        dialog.openForEdit(new BalanceDateRow(refDate, Map.of(depot.getId(), depotSnap)), () -> {
        });

        assertThat(dialog.getFieldsByAccountForTest()).containsKeys(checking.getId(), depot.getId());
        assertThat(dialog.getInvestedFieldsByAccountForTest()).containsOnlyKeys(depot.getId());
        assertThat(dialog.getInvestedFieldsByAccountForTest().get(depot.getId()).getValue())
                .isEqualByComparingTo("48000.00");

        dialog.getFieldsByAccountForTest().get(checking.getId()).setValue(new BigDecimal("1500.00"));
        dialog.getInvestedFieldsByAccountForTest().get(depot.getId()).setValue(new BigDecimal("49000.00"));
        dialog.saveForTest();

        verify(snapshotService).recordBatch(
                refDate,
                Map.of(checking.getId(), new BigDecimal("1500.00"), depot.getId(), new BigDecimal("52210.00")),
                Map.of(depot.getId(), new BigDecimal("49000.00")));
    }
}
