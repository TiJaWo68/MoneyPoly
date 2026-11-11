package de.in.mp.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import de.in.mp.admin.DatabaseResetService;
import de.in.mp.finance.Account;
import de.in.mp.finance.AccountService;
import de.in.mp.finance.AccountSnapshotService;
import de.in.mp.finance.AccountType;
import de.in.mp.finance.SnapshotValue;
import de.in.mp.importer.FinanceOdsImportService.OdsMigrationResult;
import de.in.mp.loans.LoanContract;
import de.in.mp.loans.LoanContractService;

/**
 * Tests the spreadsheet migration workflow with prepared account columns.
 */
class FinanceOdsImportServiceTest {

    private static final LocalDate SEPTEMBER = LocalDate.of(2026, 9, 30);
    private static final LocalDate OCTOBER = LocalDate.of(2026, 10, 31);

    private final List<Account> accounts = new ArrayList<>();
    private AccountService accountService;
    private AccountSnapshotService snapshotService;
    private LoanContractService loanContractService;
    private DatabaseResetService databaseResetService;
    private FinanceOdsImportService service;

    @BeforeEach
    void setUp() {
        accountService = mock(AccountService.class);
        snapshotService = mock(AccountSnapshotService.class);
        loanContractService = mock(LoanContractService.class);
        databaseResetService = mock(DatabaseResetService.class);
        service = new FinanceOdsImportService(
                accountService, snapshotService, loanContractService, databaseResetService);

        when(accountService.findAll()).thenAnswer(invocation -> List.copyOf(accounts));
        when(accountService.save(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            if (account.getId() == null) {
                account.setId(UUID.randomUUID());
                accounts.add(account);
            }
            return account;
        });
        when(snapshotService.recordSnapshots(any(), anyList()))
                .thenAnswer(invocation -> ((List<?>) invocation.getArgument(1)).size());
        when(loanContractService.findAll()).thenReturn(List.of());
    }

    @Test
    void executeCreatesInstitutionOnceAndGroupsAccounts() {
        OdsAccountColumn giro = column("Bank A", "Giro", AccountType.CHECKING, 1);
        OdsAccountColumn savings = column("Bank A", "Tagesgeld", AccountType.SAVINGS, 1);
        giro.setAccountNumber("DE02120300000000202051");

        OdsMigrationResult result = service.execute(List.of(giro, savings));

        verify(databaseResetService).resetFinanceData();
        assertThat(result.institutions()).isEqualTo(1);
        assertThat(result.accountsCreated()).isEqualTo(2);
        assertThat(result.snapshotsRecorded()).isEqualTo(2);

        Account institution = accounts.stream()
                .filter(a -> a.getAccountType() == AccountType.INSTITUTION).findFirst().orElseThrow();
        assertThat(institution.getName()).isEqualTo("Bank A");
        assertThat(accounts).filteredOn(a -> a.getAccountType() != AccountType.INSTITUTION)
                .allMatch(a -> institution.getId().equals(a.getParentId()));
        assertThat(accounts).filteredOn(a -> "Giro".equals(a.getName()))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.getAccountNumber()).isEqualTo("DE02120300000000202051");
                    assertThat(a.getDisplayOrder()).isLessThan(savingsOrder());
                });
    }

    @Test
    void executeSkipsExcludedColumnsAndKeepsInactiveFlag() {
        OdsAccountColumn excluded = column("Bank A", "Giro", AccountType.CHECKING, 1);
        excluded.setInclude(false);
        OdsAccountColumn closed = column(null, "Old Savings", AccountType.SAVINGS, 1);
        closed.setActive(false);

        OdsMigrationResult result = service.execute(List.of(excluded, closed));

        assertThat(result.accountsCreated()).isEqualTo(1);
        assertThat(result.institutions()).isZero();
        assertThat(accounts).singleElement().satisfies(a -> {
            assertThat(a.getName()).isEqualTo("Old Savings");
            assertThat(a.isActive()).isFalse();
            assertThat(a.getParentId()).isNull();
        });
    }

    @Test
    void executeMergesDetailedColumnIntoTargetWithTargetValuesLast() {
        OdsAccountColumn target = column("Bank A", "Giro", AccountType.CHECKING, 1);
        OdsAccountColumn detailed = new OdsAccountColumn("Bank A", 9, "", "Bank A Giro", List.of(
                new SnapshotValue(SEPTEMBER, new BigDecimal("900"), new BigDecimal("300"), new BigDecimal("250"),
                        null)));
        detailed.setAccountName("Bank A Giro");
        detailed.setMergeInto(target);

        OdsMigrationResult result = service.execute(List.of(target, detailed));

        assertThat(result.accountsCreated()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SnapshotValue>> values = ArgumentCaptor.forClass(List.class);
        verify(snapshotService).recordSnapshots(any(), values.capture());
        assertThat(values.getValue()).hasSize(2);
        assertThat(values.getValue().get(0).inflow()).isEqualByComparingTo("300");
        assertThat(values.getValue().get(1).date()).isEqualTo(OCTOBER);
    }

    @Test
    void executeReusesExistingAccountWhenNotReset() {
        FinanceOdsImportService serviceWithoutReset = new FinanceOdsImportService(
                accountService, snapshotService, loanContractService);
        Account existing = new Account();
        existing.setId(UUID.randomUUID());
        existing.setName("Depot");
        existing.setAccountType(AccountType.INVESTMENT);
        accounts.add(existing);
        OdsAccountColumn column = column(null, "Depot", AccountType.INVESTMENT, 1);
        column.setExistingAccountId(existing.getId());

        OdsMigrationResult result = serviceWithoutReset.execute(List.of(column));

        assertThat(result.accountsCreated()).isZero();
        verify(snapshotService).recordSnapshots(eq(existing.getId()), anyList());
    }

    @Test
    void executeResetsFinanceDataBeforeImportingAndUnifiesSharedInstitutionsByHolder() {
        Account staleAccount = new Account();
        staleAccount.setId(UUID.randomUUID());
        staleAccount.setName("Stale");
        staleAccount.setAccountType(AccountType.CHECKING);
        accounts.add(staleAccount);
        doAnswer(inv -> {
            accounts.clear();
            return null;
        }).when(databaseResetService).resetFinanceData();

        OdsAccountColumn c24Till = column("C24", null, AccountType.CHECKING, 1);
        c24Till.setAccountHolder("Till");
        OdsAccountColumn c24Birgit = column("C24", null, AccountType.CHECKING, 1);
        c24Birgit.setAccountHolder("Birgit");

        OdsMigrationResult result = service.execute(List.of(c24Till, c24Birgit));

        verify(databaseResetService).resetFinanceData();
        assertThat(result.institutions()).isEqualTo(1);
        assertThat(result.accountsCreated()).isEqualTo(2);

        List<Account> createdChecking = accounts.stream()
                .filter(a -> a.getAccountType() == AccountType.CHECKING)
                .toList();
        assertThat(createdChecking).extracting(Account::getAccountHolder)
                .containsExactly("Till", "Birgit");
        assertThat(createdChecking).allMatch(a -> a.getName() != null && !a.getName().isBlank());
    }

    @Test
    void analyzeExcludesColumnsOfPrivateLoansAndLinksExistingAccounts() {
        LoanContract loan = new LoanContract();
        loan.setDesignation("Sarah + Oli Haus");
        when(loanContractService.findAll()).thenReturn(List.of(loan));

        Account institution = new Account();
        institution.setId(UUID.randomUUID());
        institution.setName("Bank A");
        institution.setAccountType(AccountType.INSTITUTION);
        Account giro = new Account();
        giro.setId(UUID.randomUUID());
        giro.setName("Giro");
        giro.setAccountType(AccountType.CHECKING);
        giro.setParentId(institution.getId());
        giro.setAccountHolder("Max Mustermann");
        accounts.addAll(List.of(institution, giro));

        String sheet = OdsTestFiles.table("Balances",
                OdsTestFiles.row(OdsTestFiles.empty(1), OdsTestFiles.text("Datum"), OdsTestFiles.text("Gesamt"),
                        OdsTestFiles.text("Bank A"), OdsTestFiles.text("Lender")),
                OdsTestFiles.row(OdsTestFiles.empty(3), OdsTestFiles.text("Giro"), OdsTestFiles.text("Haus")),
                OdsTestFiles.row(OdsTestFiles.empty(1), OdsTestFiles.date("2026-10-31"), OdsTestFiles.number("3"),
                        OdsTestFiles.number("1"), OdsTestFiles.number("2")));

        List<OdsAccountColumn> columns = service.analyze(OdsTestFiles.ods(sheet));

        assertThat(columns).hasSize(2);
        assertThat(columns.get(0).getExistingAccountId()).isEqualTo(giro.getId());
        assertThat(columns.get(0).getAccountHolder()).isEqualTo("Max Mustermann");
        assertThat(columns.get(1).isInclude()).isFalse();
        verify(accountService, never()).save(any());
    }

    private int savingsOrder() {
        return accounts.stream().filter(a -> "Tagesgeld".equals(a.getName())).findFirst().orElseThrow()
                .getDisplayOrder();
    }

    private static OdsAccountColumn column(String institution, String name, AccountType type, int unused) {
        OdsAccountColumn column = new OdsAccountColumn("Balances", 4, institution == null ? "" : institution,
                name == null ? "" : name,
                List.of(SnapshotValue.ofBalance(OCTOBER, new BigDecimal("100"))));
        column.setInstitutionName(institution);
        column.setAccountName(name);
        column.setAccountType(type);
        column.setActive(true);
        return column;
    }
}
