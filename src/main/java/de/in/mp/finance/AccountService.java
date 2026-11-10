package de.in.mp.finance;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain service for finance accounts: validation, balance synchronization,
 * soft-delete, parent/child hierarchy and aggregate balance calculation for
 * the dashboard KPIs.
 * <p>
 * While an account has no balance snapshot, its current balance follows the
 * initial balance. Once snapshots exist, the current balance is owned by
 * {@link AccountSnapshotService} and is not changed by master data edits.
 */
@Service
public class AccountService {

    private static final int NAME_MAX_LENGTH = 100;

    private static final Pattern ISO_CURRENCY_PATTERN = Pattern.compile("[A-Z]{3}");

    private final AccountRepository accountRepository;
    private final AccountSnapshotRepository snapshotRepository;

    public AccountService(AccountRepository accountRepository, AccountSnapshotRepository snapshotRepository) {
        this.accountRepository = accountRepository;
        this.snapshotRepository = snapshotRepository;
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

        if (account.getAccountType() == AccountType.INSTITUTION) {
            account.setInitialBalance(BigDecimal.ZERO);
            account.setCurrentBalance(BigDecimal.ZERO);
        }

        if (account.getId() == null) {
            account.setCurrentBalance(account.getInitialBalance());
            return accountRepository.save(account);
        }

        Account existing = accountRepository.findById(account.getId())
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + account.getId()));

        if (snapshotRepository.findFirstByAccountIdOrderBySnapshotDateDesc(account.getId()).isPresent()) {
            account.setCurrentBalance(existing.getCurrentBalance());
        } else {
            BigDecimal initialBalanceDelta = account.getInitialBalance().subtract(existing.getInitialBalance());
            account.setCurrentBalance(existing.getCurrentBalance().add(initialBalanceDelta));
        }
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
     * Net worth = total assets (checking, savings, investment) minus loan
     * liabilities. Grouping nodes (INSTITUTION) carry no own balance and are
     * excluded.
     */
    @Transactional(readOnly = true)
    public BigDecimal calculateNetWorth() {
        return calculateTotalAssets().subtract(calculateTotalLiabilities());
    }

    @Transactional(readOnly = true)
    public BigDecimal calculateTotalAssets() {
        return findActiveAccounts().stream()
                .filter(account -> account.getAccountType() != AccountType.LOAN
                        && account.getAccountType() != AccountType.INSTITUTION)
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

    /**
     * Liquid funds: active checking and savings balances.
     */
    @Transactional(readOnly = true)
    public BigDecimal calculateLiquidity() {
        return findActiveAccounts().stream()
                .filter(account -> account.getAccountType() == AccountType.CHECKING
                        || account.getAccountType() == AccountType.SAVINGS)
                .map(Account::getCurrentBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Long-term securities: active investment (brokerage) balances.
     */
    @Transactional(readOnly = true)
    public BigDecimal calculateInvestedAssets() {
        return findActiveAccounts().stream()
                .filter(account -> account.getAccountType() == AccountType.INVESTMENT)
                .map(Account::getCurrentBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Aggregated balance per institution: the sum of all active, non-institution
     * descendant balances within the institution subtree.
     */
    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> calculateInstitutionTotals() {
        List<Account> all = accountRepository.findAllByOrderByAccountTypeAscNameAsc();
        Map<UUID, List<Account>> childrenByParent = all.stream()
                .filter(account -> account.getParentId() != null)
                .collect(Collectors.groupingBy(Account::getParentId));

        Map<UUID, BigDecimal> totals = new HashMap<>();
        for (Account account : all) {
            if (account.getAccountType() == AccountType.INSTITUTION) {
                totals.put(account.getId(), sumSubtree(account.getId(), childrenByParent));
            }
        }
        return totals;
    }

    private BigDecimal sumSubtree(UUID parentId, Map<UUID, List<Account>> childrenByParent) {
        BigDecimal total = BigDecimal.ZERO;
        for (Account child : childrenByParent.getOrDefault(parentId, List.of())) {
            if (!child.isActive()) {
                continue;
            }
            if (child.getAccountType() != AccountType.INSTITUTION) {
                total = total.add(child.getCurrentBalance());
            }
            total = total.add(sumSubtree(child.getId(), childrenByParent));
        }
        return total;
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
        validateParent(account);
    }

    private void validateParent(Account account) {
        UUID parentId = account.getParentId();
        if (parentId == null) {
            return;
        }
        if (account.getId() != null && parentId.equals(account.getId())) {
            throw new IllegalArgumentException("Account cannot be its own parent");
        }

        Set<UUID> visited = new HashSet<>();
        if (account.getId() != null) {
            visited.add(account.getId());
        }

        Account cursor = accountRepository.findById(parentId)
                .orElseThrow(() -> new IllegalArgumentException("Parent account not found: " + parentId));
        while (cursor != null) {
            if (!visited.add(cursor.getId())) {
                throw new IllegalArgumentException("Circular parent relation detected");
            }
            UUID nextId = cursor.getParentId();
            cursor = nextId == null ? null : accountRepository.findById(nextId).orElse(null);
        }
    }
}
