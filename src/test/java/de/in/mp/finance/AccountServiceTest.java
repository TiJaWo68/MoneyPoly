package de.in.mp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AccountService} using a mocked repository.
 */
class AccountServiceTest {

    private AccountRepository repository;
    private AccountSnapshotRepository snapshotRepository;
    private AccountService service;

    @BeforeEach
    void setUp() {
        repository = mock(AccountRepository.class);
        snapshotRepository = mock(AccountSnapshotRepository.class);
        service = new AccountService(repository, snapshotRepository);
    }

    @Test
    void saveNewAccountSynchronizesCurrentBalanceWithInitialBalance() {
        when(repository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Account account = account(AccountType.CHECKING, BigDecimal.ZERO);
        account.setInitialBalance(new BigDecimal("1500.00"));

        Account saved = service.save(account);

        assertThat(saved.getCurrentBalance()).isEqualByComparingTo("1500.00");
        verify(repository).save(account);
    }

    @Test
    void saveExistingAccountAdjustsCurrentBalanceByInitialBalanceDelta() {
        UUID id = UUID.randomUUID();

        Account existing = account(AccountType.SAVINGS, new BigDecimal("1500.00"));
        existing.setId(id);
        existing.setInitialBalance(new BigDecimal("1000.00"));

        when(repository.findById(id)).thenReturn(Optional.of(existing));
        when(repository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Account update = account(AccountType.SAVINGS, BigDecimal.ZERO);
        update.setId(id);
        update.setInitialBalance(new BigDecimal("1200.00"));

        Account saved = service.save(update);

        assertThat(saved.getCurrentBalance()).isEqualByComparingTo("1700.00");
    }

    @Test
    void saveInstitutionForcesZeroBalances() {
        when(repository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Account institution = account(AccountType.INSTITUTION, new BigDecimal("500.00"));
        institution.setInitialBalance(new BigDecimal("500.00"));

        Account saved = service.save(institution);

        assertThat(saved.getInitialBalance()).isEqualByComparingTo("0");
        assertThat(saved.getCurrentBalance()).isEqualByComparingTo("0");
    }

    @Test
    void saveRejectsBlankName() {
        Account account = account(AccountType.CHECKING, BigDecimal.ZERO);
        account.setName("   ");

        assertThatThrownBy(() -> service.save(account))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void saveRejectsInvalidCurrency() {
        Account account = account(AccountType.CHECKING, BigDecimal.ZERO);
        account.setCurrency("EURO");

        assertThatThrownBy(() -> service.save(account))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO");
    }

    @Test
    void saveRejectsMissingAccountType() {
        Account account = new Account();
        account.setName("C24");
        account.setCurrency("EUR");

        assertThatThrownBy(() -> service.save(account))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type");
    }

    @Test
    void saveRejectsSelfAsParent() {
        UUID id = UUID.randomUUID();
        Account account = account(AccountType.CHECKING, BigDecimal.ZERO);
        account.setId(id);
        account.setParentId(id);

        assertThatThrownBy(() -> service.save(account))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("own parent");
    }

    @Test
    void saveRejectsMissingParent() {
        UUID parentId = UUID.randomUUID();
        Account account = account(AccountType.CHECKING, BigDecimal.ZERO);
        account.setParentId(parentId);

        when(repository.findById(parentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(account))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Parent account not found");
    }

    @Test
    void saveRejectsCircularParent() {
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();

        Account accountA = account(AccountType.CHECKING, BigDecimal.ZERO);
        accountA.setId(idA);
        accountA.setParentId(idB);

        Account accountB = account(AccountType.CHECKING, BigDecimal.ZERO);
        accountB.setId(idB);
        accountB.setParentId(idA);

        when(repository.findById(idB)).thenReturn(Optional.of(accountB));
        when(repository.findById(idA)).thenReturn(Optional.of(accountA));

        assertThatThrownBy(() -> service.save(accountA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Circular parent");
    }

    @Test
    void deactivateSetsActiveFalse() {
        UUID id = UUID.randomUUID();
        Account account = account(AccountType.LOAN, BigDecimal.ZERO);
        account.setId(id);
        account.setActive(true);

        when(repository.findById(id)).thenReturn(Optional.of(account));
        when(repository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.deactivate(id);

        assertThat(account.isActive()).isFalse();
        verify(repository).save(account);
    }

    @Test
    void activateSetsActiveTrue() {
        UUID id = UUID.randomUUID();
        Account account = account(AccountType.CHECKING, BigDecimal.ZERO);
        account.setId(id);
        account.setActive(false);

        when(repository.findById(id)).thenReturn(Optional.of(account));
        when(repository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.activate(id);

        assertThat(account.isActive()).isTrue();
        verify(repository).save(account);
    }

    @Test
    void calculateNetWorthSubtractsLoanLiabilities() {
        when(repository.findByActiveTrueOrderByAccountTypeAscNameAsc()).thenReturn(List.of(
                account(AccountType.CHECKING, new BigDecimal("1000.00")),
                account(AccountType.SAVINGS, new BigDecimal("500.00")),
                account(AccountType.INVESTMENT, new BigDecimal("2500.00")),
                account(AccountType.LOAN, new BigDecimal("800.00"))));

        assertThat(service.calculateNetWorth()).isEqualByComparingTo("3200.00");
    }

    @Test
    void calculateNetWorthExcludesInstitutions() {
        when(repository.findByActiveTrueOrderByAccountTypeAscNameAsc()).thenReturn(List.of(
                account(AccountType.CHECKING, new BigDecimal("1000.00")),
                account(AccountType.SAVINGS, new BigDecimal("500.00")),
                account(AccountType.INVESTMENT, new BigDecimal("2500.00")),
                account(AccountType.LOAN, new BigDecimal("800.00")),
                account(AccountType.INSTITUTION, new BigDecimal("9999.00"))));

        assertThat(service.calculateNetWorth()).isEqualByComparingTo("3200.00");
    }

    @Test
    void calculateInstitutionTotalsSumsDescendantBalances() {
        UUID institutionId = UUID.randomUUID();

        Account institution = account(AccountType.INSTITUTION, BigDecimal.ZERO);
        institution.setId(institutionId);

        Account cash = account(AccountType.CHECKING, new BigDecimal("100.00"));
        cash.setId(UUID.randomUUID());
        cash.setParentId(institutionId);

        Account depot = account(AccountType.INVESTMENT, new BigDecimal("200.00"));
        depot.setId(UUID.randomUUID());
        depot.setParentId(institutionId);

        Account pocket = account(AccountType.SAVINGS, new BigDecimal("50.00"));
        pocket.setId(UUID.randomUUID());
        pocket.setParentId(cash.getId());

        when(repository.findAllByOrderByAccountTypeAscNameAsc())
                .thenReturn(List.of(institution, cash, depot, pocket));

        Map<UUID, BigDecimal> totals = service.calculateInstitutionTotals();

        assertThat(totals.get(institutionId)).isEqualByComparingTo("350.00");
    }

    @Test
    void countActiveAccountsDelegatesToRepository() {
        when(repository.countByActiveTrue()).thenReturn(3L);

        assertThat(service.countActiveAccounts()).isEqualTo(3L);
    }

    @Test
    void calculateLiquiditySumsCheckingAndSavingsBalances() {
        when(repository.findByActiveTrueOrderByAccountTypeAscNameAsc()).thenReturn(List.of(
                account(AccountType.CHECKING, new BigDecimal("1000.00")),
                account(AccountType.SAVINGS, new BigDecimal("500.00")),
                account(AccountType.INVESTMENT, new BigDecimal("2500.00")),
                account(AccountType.LOAN, new BigDecimal("800.00"))));

        assertThat(service.calculateLiquidity()).isEqualByComparingTo("1500.00");
    }

    @Test
    void calculateInvestedAssetsSumsInvestmentBalances() {
        when(repository.findByActiveTrueOrderByAccountTypeAscNameAsc()).thenReturn(List.of(
                account(AccountType.CHECKING, new BigDecimal("1000.00")),
                account(AccountType.SAVINGS, new BigDecimal("500.00")),
                account(AccountType.INVESTMENT, new BigDecimal("2500.00")),
                account(AccountType.INVESTMENT, new BigDecimal("1500.00"))));

        assertThat(service.calculateInvestedAssets()).isEqualByComparingTo("4000.00");
    }

    @Test
    void saveRejectsNegativePromotionalInterestRate() {
        Account account = account(AccountType.SAVINGS, BigDecimal.ZERO);
        account.setPromotionalInterestRate(new BigDecimal("-0.50"));

        assertThatThrownBy(() -> service.save(account))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void saveRejectsNegativeSubsequentInterestRate() {
        Account account = account(AccountType.SAVINGS, BigDecimal.ZERO);
        account.setSubsequentInterestRate(new BigDecimal("-0.01"));

        assertThatThrownBy(() -> service.save(account))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void saveClearsInterestRatesForNonSavingsAccount() {
        when(repository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Account account = account(AccountType.CHECKING, BigDecimal.ZERO);
        account.setPromotionalInterestRate(new BigDecimal("3.00"));
        account.setPromotionalUntil(java.time.LocalDate.of(2026, 12, 31));
        account.setSubsequentInterestRate(new BigDecimal("1.50"));

        Account saved = service.save(account);

        assertThat(saved.getPromotionalInterestRate()).isNull();
        assertThat(saved.getPromotionalUntil()).isNull();
        assertThat(saved.getSubsequentInterestRate()).isNull();
    }

    private Account account(AccountType type, BigDecimal currentBalance) {
        Account account = new Account();
        account.setName("account-" + type.name());
        account.setAccountType(type);
        account.setCurrency("EUR");
        account.setCurrentBalance(currentBalance);
        return account;
    }
}
