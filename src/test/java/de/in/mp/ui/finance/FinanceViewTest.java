package de.in.mp.ui.finance;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.in.mp.finance.Account;
import de.in.mp.finance.AccountService;
import de.in.mp.finance.AccountSnapshot;
import de.in.mp.finance.AccountSnapshotService;
import de.in.mp.finance.AccountType;
import de.in.mp.finance.BalanceDateRow;
import de.in.mp.finance.BankLookupService;
import de.in.mp.importer.StatementImportService;

class FinanceViewTest {

    @Test
    void instantiatesAndRebuildsGroupedBalanceColumnsWithoutException() {
        AccountService accountService = mock(AccountService.class);
        AccountSnapshotService snapshotService = mock(AccountSnapshotService.class);
        BankLookupService bankLookupService = mock(BankLookupService.class);
        StatementImportService statementImportService = mock(StatementImportService.class);

        Account c24 = institution(UUID.randomUUID(), "C24");
        Account c24Main = childAccount(UUID.randomUUID(), "Hauptkonto", c24.getId(), 1);
        Account c24Pocket = childAccount(UUID.randomUUID(), "Tagesgeld", c24.getId(), 2);

        Account targobank = institution(UUID.randomUUID(), "Targobank");
        Account targoChecking = childAccount(UUID.randomUUID(), "Giro", targobank.getId(), 3);
        Account targoSavings = childAccount(UUID.randomUUID(), "Sparen", targobank.getId(), 4);

        Account dkb = institution(UUID.randomUUID(), "DKB");
        Account dkbChecking = childAccount(UUID.randomUUID(), "DKB Giro", dkb.getId(), 5);
        Account dkbSavings = childAccount(UUID.randomUUID(), "DKB Sparen", dkb.getId(), 6);

        Account extra = childAccount(UUID.randomUUID(), "Extra", null, 7);

        List<Account> accounts = List.of(
                c24, c24Main, c24Pocket,
                targobank, targoChecking, targoSavings,
                dkb, dkbChecking, dkbSavings,
                extra);
        when(accountService.findAll()).thenReturn(accounts);
        when(accountService.calculateInstitutionTotals()).thenReturn(Map.of());
        when(accountService.calculateNetWorth()).thenReturn(BigDecimal.ZERO);
        when(accountService.countActiveAccounts()).thenReturn(4L);

        LocalDate date = LocalDate.of(2026, 1, 1);
        AccountSnapshot snap = new AccountSnapshot();
        snap.setAccountId(c24Main.getId());
        snap.setSnapshotDate(date);
        snap.setBalance(new BigDecimal("100.00"));
        when(snapshotService.buildDateRows()).thenReturn(List.of(new BalanceDateRow(date, Map.of(c24Main.getId(), snap))));

        assertThatCode(() -> new FinanceView(accountService, snapshotService, bankLookupService, statementImportService))
                .doesNotThrowAnyException();
    }

    private static Account institution(UUID id, String name) {
        Account account = new Account();
        account.setId(id);
        account.setName(name);
        account.setAccountType(AccountType.INSTITUTION);
        account.setActive(true);
        return account;
    }

    private static Account childAccount(UUID id, String name, UUID parentId, int displayOrder) {
        Account account = new Account();
        account.setId(id);
        account.setName(name);
        account.setAccountType(AccountType.CHECKING);
        account.setParentId(parentId);
        account.setDisplayOrder(displayOrder);
        account.setActive(true);
        return account;
    }
}
