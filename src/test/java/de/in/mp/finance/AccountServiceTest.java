package de.in.mp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AccountService} using a mocked repository.
 */
class AccountServiceTest {

    private AccountRepository repository;
    private AccountService service;

    @BeforeEach
    void setUp() {
        repository = mock(AccountRepository.class);
        service = new AccountService(repository);
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
    void countActiveAccountsDelegatesToRepository() {
        when(repository.countByActiveTrue()).thenReturn(3L);

        assertThat(service.countActiveAccounts()).isEqualTo(3L);
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
