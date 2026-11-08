package de.in.mp.finance;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain service for finance accounts: validation, balance synchronization,
 * soft-delete and aggregate balance calculation for the dashboard KPIs.
 */
@Service
public class AccountService {

    private static final int NAME_MAX_LENGTH = 100;

    private static final Pattern ISO_CURRENCY_PATTERN = Pattern.compile("[A-Z]{3}");

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public List<Account> findAll() {
        return accountRepository.findAllByOrderByAccountTypeAscNameAsc();
    }

    @Transactional(readOnly = true)
    public List<Account> findActiveAccounts() {
        return accountRepository.findByActiveTrueOrderByAccountTypeAscNameAsc();
    }

    @Transactional
    public Account save(Account account) {
        validate(account);

        if (account.getId() == null) {
            account.setCurrentBalance(account.getInitialBalance());
            return accountRepository.save(account);
        }

        Account existing = accountRepository.findById(account.getId())
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + account.getId()));

        BigDecimal initialBalanceDelta = account.getInitialBalance().subtract(existing.getInitialBalance());
        account.setCurrentBalance(existing.getCurrentBalance().add(initialBalanceDelta));
        return accountRepository.save(account);
    }

    @Transactional
    public void deactivate(UUID id) {
        Account account = requireAccount(id);
        account.setActive(false);
        accountRepository.save(account);
    }

    @Transactional
    public void activate(UUID id) {
        Account account = requireAccount(id);
        account.setActive(true);
        accountRepository.save(account);
    }

    @Transactional(readOnly = true)
    public long countActiveAccounts() {
        return accountRepository.countByActiveTrue();
    }

    /**
     * Net worth = total assets (checking, savings, investment) minus loan liabilities.
     */
    @Transactional(readOnly = true)
    public BigDecimal calculateNetWorth() {
        return calculateTotalAssets().subtract(calculateTotalLiabilities());
    }

    @Transactional(readOnly = true)
    public BigDecimal calculateTotalAssets() {
        return findActiveAccounts().stream()
                .filter(account -> account.getAccountType() != AccountType.LOAN)
                .map(Account::getCurrentBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Transactional(readOnly = true)
    public BigDecimal calculateTotalLiabilities() {
        return findActiveAccounts().stream()
                .filter(account -> account.getAccountType() == AccountType.LOAN)
                .map(Account::getCurrentBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Account requireAccount(UUID id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + id));
    }

    private void validate(Account account) {
        if (account == null) {
            throw new IllegalArgumentException("Account must not be null");
        }
        if (account.getName() == null || account.getName().isBlank()) {
            throw new IllegalArgumentException("Account name is required");
        }
        if (account.getName().length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("Account name must not exceed " + NAME_MAX_LENGTH + " characters");
        }
        if (account.getAccountType() == null) {
            throw new IllegalArgumentException("Account type is required");
        }
        if (account.getCurrency() == null || !ISO_CURRENCY_PATTERN.matcher(account.getCurrency()).matches()) {
            throw new IllegalArgumentException("Currency must be a 3-letter ISO code");
        }
        if (account.getInitialBalance() == null) {
            account.setInitialBalance(BigDecimal.ZERO);
        }
        if (account.getCurrentBalance() == null) {
            account.setCurrentBalance(BigDecimal.ZERO);
        }
    }
}
