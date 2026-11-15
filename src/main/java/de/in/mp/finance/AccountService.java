package de.in.mp.finance;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain service for finance accounts: validation, soft-delete, parent/child
 * hierarchy, dynamic balance resolution and aggregate KPI calculation.
 * <p>
 * Current balances are derived from a single source of truth:
 * {@link PortfolioPosition} valuations for position-managed depots, or the most
 * recent {@link AccountSnapshot} (falling back to {@code initialBalance}) for
 * all other accounts.
 */
@Service
public class AccountService {

    private static final int NAME_MAX_LENGTH = 100;

    private static final Pattern ISO_CURRENCY_PATTERN = Pattern.compile("[A-Z]{3}");

    private final AccountRepository accountRepository;
    private final AccountSnapshotRepository snapshotRepository;
    private final HouseholdService householdService;
    private final PortfolioPositionRepository positionRepository;

    public AccountService(AccountRepository accountRepository, AccountSnapshotRepository snapshotRepository) {
        this(accountRepository, snapshotRepository, null, null);
    }

    public AccountService(
            AccountRepository accountRepository,
            AccountSnapshotRepository snapshotRepository,
            HouseholdService householdService) {
        this(accountRepository, snapshotRepository, householdService, null);
    }

    @Autowired
    public AccountService(
            AccountRepository accountRepository,
            AccountSnapshotRepository snapshotRepository,
            @Autowired(required = false) HouseholdService householdService,
            @Autowired(required = false) PortfolioPositionRepository positionRepository) {
        this.accountRepository = accountRepository;
        this.snapshotRepository = snapshotRepository;
        this.householdService = householdService;
        this.positionRepository = positionRepository;
    }

    @Transactional(readOnly = true)
    public List<Account> findAll() {
        UUID householdId = resolveCurrentHouseholdId();
        return householdId == null
                ? accountRepository.findAllByOrderByAccountTypeAscNameAsc()
                : accountRepository.findByHouseholdIdOrderByAccountTypeAscNameAsc(householdId);
    }

    @Transactional(readOnly = true)
    public List<Account> findAllByHousehold(UUID householdId) {
        UUID effectiveId = resolveEffectiveHouseholdId(householdId);
        return effectiveId == null
                ? accountRepository.findAllByOrderByAccountTypeAscNameAsc()
                : accountRepository.findByHouseholdIdOrderByAccountTypeAscNameAsc(effectiveId);
    }

    @Transactional(readOnly = true)
    public List<Account> findActiveAccounts() {
        UUID householdId = resolveCurrentHouseholdId();
        return householdId == null
                ? accountRepository.findByActiveTrueOrderByAccountTypeAscNameAsc()
                : accountRepository.findByHouseholdIdAndActiveTrueOrderByAccountTypeAscNameAsc(householdId);
    }

    @Transactional(readOnly = true)
    public List<Account> findActiveAccountsByHousehold(UUID householdId) {
        UUID effectiveId = resolveEffectiveHouseholdId(householdId);
        return effectiveId == null
                ? accountRepository.findByActiveTrueOrderByAccountTypeAscNameAsc()
                : accountRepository.findByHouseholdIdAndActiveTrueOrderByAccountTypeAscNameAsc(effectiveId);
    }

    @Transactional
    public Account save(Account account) {
        if (account != null && account.getHouseholdId() == null) {
            account.setHouseholdId(resolveCurrentHouseholdId());
        }
        validate(account);

        if (account.getAccountType() == AccountType.INSTITUTION) {
            account.setInitialBalance(BigDecimal.ZERO);
        }
        if (account.getAccountType() != AccountType.SAVINGS) {
            account.setPromotionalInterestRate(null);
            account.setPromotionalUntil(null);
            account.setSubsequentInterestRate(null);
        }

        if (account.getId() != null) {
            accountRepository.findById(account.getId())
                    .orElseThrow(() -> new IllegalArgumentException("Account not found: " + account.getId()));
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
        UUID householdId = resolveCurrentHouseholdId();
        return householdId == null
                ? accountRepository.countByActiveTrue()
                : accountRepository.countByHouseholdIdAndActiveTrue(householdId);
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
        return sumAccounts(findActiveAccounts(), false);
    }

    @Transactional(readOnly = true)
    public BigDecimal calculateTotalAssets(UUID householdId) {
        return sumAccounts(findActiveAccountsByHousehold(householdId), false);
    }

    @Transactional(readOnly = true)
    public BigDecimal calculateTotalLiabilities() {
        return sumAccounts(findActiveAccounts(), true);
    }

    @Transactional(readOnly = true)
    public BigDecimal calculateTotalLiabilities(UUID householdId) {
        return sumAccounts(findActiveAccountsByHousehold(householdId), true);
    }

    /**
     * Liquid funds: active checking and savings balances.
     */
    @Transactional(readOnly = true)
    public BigDecimal calculateLiquidity() {
        return sumLiquidity(findActiveAccounts());
    }

    @Transactional(readOnly = true)
    public BigDecimal calculateLiquidity(UUID householdId) {
        return sumLiquidity(findActiveAccountsByHousehold(householdId));
    }

    /**
     * Long-term securities: active investment (brokerage) balances.
     */
    @Transactional(readOnly = true)
    public BigDecimal calculateInvestedAssets() {
        return sumInvested(findActiveAccounts());
    }

    @Transactional(readOnly = true)
    public BigDecimal calculateInvestedAssets(UUID householdId) {
        return sumInvested(findActiveAccountsByHousehold(householdId));
    }

    /**
     * Aggregated balance per institution: the sum of all active, non-institution
     * descendant balances within the institution subtree.
     */
    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> calculateInstitutionTotals() {
        return calculateInstitutionTotals(findAll());
    }

    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> calculateInstitutionTotals(UUID householdId) {
        return calculateInstitutionTotals(findAllByHousehold(householdId));
    }

    /**
     * Resolves the current balance of a single account without persisted cache
     * state: {@link PortfolioPosition} valuation for position-managed depots, or
     * the latest {@link AccountSnapshot} (falling back to {@code initialBalance}).
     */
    @Transactional(readOnly = true)
    public BigDecimal resolveCurrentBalance(Account account) {
        if (account == null || account.getAccountType() == AccountType.INSTITUTION) {
            return BigDecimal.ZERO;
        }
        if (account.getId() == null) {
            return safeMoney(account.getInitialBalance());
        }
        return resolveCurrentBalances(List.of(account)).getOrDefault(account.getId(), BigDecimal.ZERO);
    }

    /**
     * Resolves current balances for all given accounts in batch (at most two
     * queries: portfolio positions for depots and latest snapshots for the rest).
     */
    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> resolveCurrentBalances(Collection<Account> accounts) {
        Map<UUID, BigDecimal> resolved = new HashMap<>();
        if (accounts == null || accounts.isEmpty()) {
            return resolved;
        }
        List<UUID> investmentIds = accounts.stream()
                .filter(a -> a != null && a.getId() != null && a.getAccountType() == AccountType.INVESTMENT)
                .map(Account::getId)
                .distinct()
                .toList();
        Map<UUID, List<PortfolioPosition>> positionsByAccount = (positionRepository == null || investmentIds.isEmpty())
                ? Map.of()
                : positionRepository.findByAccountIdIn(investmentIds).stream()
                        .collect(Collectors.groupingBy(PortfolioPosition::getAccountId));

        List<UUID> snapshotQueryIds = new java.util.ArrayList<>();
        for (Account account : accounts) {
            if (account == null || account.getId() == null) {
                continue;
            }
            if (account.getAccountType() == AccountType.INSTITUTION) {
                resolved.put(account.getId(), BigDecimal.ZERO);
                continue;
            }
            List<PortfolioPosition> positions = positionsByAccount.getOrDefault(account.getId(), List.of());
            if (!positions.isEmpty()) {
                resolved.put(account.getId(),
                        PortfolioPositionService.buildPositionValuation(account.getId(), positions).currentValue());
            } else {
                snapshotQueryIds.add(account.getId());
            }
        }

        Map<UUID, BigDecimal> latestSnapshots = new HashMap<>();
        if (!snapshotQueryIds.isEmpty()) {
            for (AccountSnapshot snap : snapshotRepository.findLatestByAccountIds(snapshotQueryIds)) {
                latestSnapshots.putIfAbsent(snap.getAccountId(), safeMoney(snap.getBalance()));
            }
        }
        for (Account account : accounts) {
            if (account == null || account.getId() == null || resolved.containsKey(account.getId())) {
                continue;
            }
            BigDecimal value = latestSnapshots.containsKey(account.getId())
                    ? latestSnapshots.get(account.getId())
                    : safeMoney(account.getInitialBalance());
            resolved.put(account.getId(), value);
        }
        return resolved;
    }

    private Map<UUID, BigDecimal> calculateInstitutionTotals(List<Account> all) {
        Map<UUID, BigDecimal> balances = resolveCurrentBalances(all);
        Map<UUID, List<Account>> childrenByParent = all.stream()
                .filter(account -> account.getParentId() != null)
                .collect(Collectors.groupingBy(Account::getParentId));

        Map<UUID, BigDecimal> totals = new HashMap<>();
        for (Account account : all) {
            if (account.getAccountType() == AccountType.INSTITUTION) {
                totals.put(account.getId(), sumSubtree(account.getId(), childrenByParent, balances));
            }
        }
        return totals;
    }

    private BigDecimal sumAccounts(List<Account> accounts, boolean loansOnly) {
        Map<UUID, BigDecimal> balances = resolveCurrentBalances(accounts);
        return accounts.stream()
                .filter(account -> loansOnly
                        ? account.getAccountType() == AccountType.LOAN
                        : account.getAccountType() != AccountType.LOAN
                                && account.getAccountType() != AccountType.INSTITUTION)
                .map(account -> balanceFromMap(account, balances))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumLiquidity(List<Account> accounts) {
        Map<UUID, BigDecimal> balances = resolveCurrentBalances(accounts);
        return accounts.stream()
                .filter(account -> account.getAccountType() == AccountType.CHECKING
                        || account.getAccountType() == AccountType.SAVINGS)
                .map(account -> balanceFromMap(account, balances))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumInvested(List<Account> accounts) {
        Map<UUID, BigDecimal> balances = resolveCurrentBalances(accounts);
        return accounts.stream()
                .filter(account -> account.getAccountType() == AccountType.INVESTMENT)
                .map(account -> balanceFromMap(account, balances))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal balanceFromMap(Account account, Map<UUID, BigDecimal> balances) {
        if (account == null || account.getAccountType() == AccountType.INSTITUTION) {
            return BigDecimal.ZERO;
        }
        return account.getId() != null
                ? balances.getOrDefault(account.getId(), BigDecimal.ZERO)
                : safeMoney(account.getInitialBalance());
    }

    private static BigDecimal safeMoney(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private UUID resolveCurrentHouseholdId() {
        return householdService == null ? null : householdService.resolveEffectiveHouseholdId(null);
    }

    private UUID resolveEffectiveHouseholdId(UUID requestedHouseholdId) {
        return householdService == null ? requestedHouseholdId : householdService.resolveEffectiveHouseholdId(requestedHouseholdId);
    }

    private BigDecimal sumSubtree(UUID parentId, Map<UUID, List<Account>> childrenByParent,
            Map<UUID, BigDecimal> balances) {
        BigDecimal total = BigDecimal.ZERO;
        for (Account child : childrenByParent.getOrDefault(parentId, List.of())) {
            if (!child.isActive()) {
                continue;
            }
            if (child.getAccountType() != AccountType.INSTITUTION) {
                total = total.add(balanceFromMap(child, balances));
            }
            total = total.add(sumSubtree(child.getId(), childrenByParent, balances));
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
        if (account.getAccountType() == AccountType.SAVINGS) {
            if (account.getPromotionalInterestRate() != null
                    && account.getPromotionalInterestRate().compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("Promotional interest rate cannot be negative");
            }
            if (account.getSubsequentInterestRate() != null
                    && account.getSubsequentInterestRate().compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("Subsequent interest rate cannot be negative");
            }
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
