package de.in.mp.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains the balance history of finance accounts.
 * <p>
 * Snapshots are upserted per (account, date). Account balances are derived
 * dynamically from the latest snapshot by {@link AccountService} without
 * duplicating balance state onto the account entity.
 */
@Service
public class AccountSnapshotService {

    private static final int MONEY_SCALE = 2;

    private final AccountSnapshotRepository snapshotRepository;
    private final AccountRepository accountRepository;
    private final HouseholdService householdService;

    public AccountSnapshotService(AccountSnapshotRepository snapshotRepository,
            AccountRepository accountRepository) {
        this(snapshotRepository, accountRepository, null);
    }

    @Autowired
    public AccountSnapshotService(
            AccountSnapshotRepository snapshotRepository,
            AccountRepository accountRepository,
            @Autowired(required = false) HouseholdService householdService) {
        this.snapshotRepository = snapshotRepository;
        this.accountRepository = accountRepository;
        this.householdService = householdService;
    }

    /**
     * Creates or updates the snapshot of an account at the given date.
     * {@code inflow}/{@code outflow}/{@code notes} are optional; {@code null}
     * keeps an already stored value untouched.
     */
    @Transactional
    public AccountSnapshot recordSnapshot(UUID accountId, SnapshotValue value) {
        requireBalanceAccount(accountId);
        return upsert(accountId, value);
    }

    /**
     * Records many snapshots of one account (e.g. a historical migration).
     */
    @Transactional
    public int recordSnapshots(UUID accountId, List<SnapshotValue> values) {
        requireBalanceAccount(accountId);
        for (SnapshotValue value : values) {
            upsert(accountId, value);
        }
        return values.size();
    }

    /**
     * Records the balances of several accounts for one reference date, e.g.
     * from the quick-entry dialog. Accounts mapped to {@code null} are skipped.
     */
    @Transactional
    public int recordBatch(LocalDate date, Map<UUID, BigDecimal> balancesByAccount) {
        return recordBatch(date, balancesByAccount, Map.of());
    }

    /**
     * Records the balances and optional invested capital (cost basis) of several
     * accounts for one reference date. If {@code investedCapitalByAccount}
     * explicitly maps an account to {@code null}, any previously stored
     * invested capital on that date is cleared.
     */
    @Transactional
    public int recordBatch(
            LocalDate date,
            Map<UUID, BigDecimal> balancesByAccount,
            Map<UUID, BigDecimal> investedCapitalByAccount) {
        int savedCount = 0;
        for (Map.Entry<UUID, BigDecimal> entry : balancesByAccount.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            UUID accountId = entry.getKey();
            boolean hasInvestedEntry = investedCapitalByAccount != null
                    && investedCapitalByAccount.containsKey(accountId);
            BigDecimal invested = hasInvestedEntry ? investedCapitalByAccount.get(accountId) : null;
            AccountSnapshot saved = recordSnapshot(
                    accountId,
                    SnapshotValue.ofBalanceAndInvestedCapital(date, entry.getValue(), invested));
            if (hasInvestedEntry && invested == null && saved.getInvestedCapital() != null) {
                saved.setInvestedCapital(null);
                snapshotRepository.save(saved);
            }
            savedCount++;
        }
        return savedCount;
    }

    /**
     * Removes all snapshots of a reference date (one row in the balance matrix).
     */
    @Transactional
    public void deleteSnapshotsForDate(LocalDate date) {
        UUID householdId = householdService == null ? null : householdService.resolveEffectiveHouseholdId(null);
        deleteSnapshotsForDate(householdId, date);
    }

    @Transactional
    public void deleteSnapshotsForDate(UUID householdId, LocalDate date) {
        UUID effectiveId = householdService == null ? householdId : householdService.resolveEffectiveHouseholdId(householdId);
        List<AccountSnapshot> snapshots = snapshotRepository.findBySnapshotDate(date);
        if (effectiveId != null) {
            Set<UUID> householdAccountIds = accountRepository.findByHouseholdIdOrderByAccountTypeAscNameAsc(effectiveId)
                    .stream()
                    .map(Account::getId)
                    .collect(Collectors.toSet());
            snapshots = snapshots.stream()
                    .filter(s -> householdAccountIds.contains(s.getAccountId()))
                    .toList();
        }
        snapshotRepository.deleteAll(snapshots);
    }

    @Transactional(readOnly = true)
    public Optional<AccountSnapshot> findLatest(UUID accountId) {
        return snapshotRepository.findFirstByAccountIdOrderBySnapshotDateDesc(accountId);
    }

    @Transactional(readOnly = true)
    public Map<UUID, AccountSnapshot> findLatestSnapshots(Collection<UUID> accountIds) {
        Map<UUID, AccountSnapshot> result = new HashMap<>();
        if (accountIds == null) {
            return result;
        }
        for (UUID accountId : accountIds) {
            if (accountId != null) {
                findLatest(accountId).ifPresent(snapshot -> result.put(accountId, snapshot));
            }
        }
        return result;
    }

    @Transactional(readOnly = true)
    public boolean hasSnapshots(UUID accountId) {
        return accountId != null && findLatest(accountId).isPresent();
    }

    /**
     * Builds the balance matrix: one row per reference date (newest first),
     * each row mapping account ids to their snapshot at that date.
     */
    @Transactional(readOnly = true)
    public List<BalanceDateRow> buildDateRows() {
        UUID householdId = householdService == null ? null : householdService.resolveEffectiveHouseholdId(null);
        return buildDateRows(householdId);
    }

    @Transactional(readOnly = true)
    public List<BalanceDateRow> buildDateRows(UUID householdId) {
        UUID effectiveId = householdService == null ? householdId : householdService.resolveEffectiveHouseholdId(householdId);
        Set<UUID> allowedAccountIds = effectiveId == null
                ? null
                : accountRepository.findByHouseholdIdOrderByAccountTypeAscNameAsc(effectiveId).stream()
                        .map(Account::getId)
                        .collect(Collectors.toSet());
        Map<LocalDate, Map<UUID, AccountSnapshot>> byDate = new TreeMap<>(Comparator.reverseOrder());
        for (AccountSnapshot snapshot : snapshotRepository.findAllByOrderBySnapshotDateDesc()) {
            if (allowedAccountIds != null && !allowedAccountIds.contains(snapshot.getAccountId())) {
                continue;
            }
            byDate.computeIfAbsent(snapshot.getSnapshotDate(), key -> new LinkedHashMap<>())
                    .put(snapshot.getAccountId(), snapshot);
        }
        List<BalanceDateRow> rows = new ArrayList<>(byDate.size());
        byDate.forEach((date, snapshots) -> rows.add(new BalanceDateRow(date, snapshots)));
        return rows;
    }

    /**
     * Expected closing balance derived from the previous balance and the
     * monthly flows: {@code previous + inflow - outflow}.
     */
    public static BigDecimal expectedBalance(BigDecimal previousBalance, BigDecimal inflow, BigDecimal outflow) {
        BigDecimal result = previousBalance == null ? BigDecimal.ZERO : previousBalance;
        if (inflow != null) {
            result = result.add(inflow.abs());
        }
        if (outflow != null) {
            result = result.subtract(outflow.abs());
        }
        return scale(result);
    }

    private AccountSnapshot upsert(UUID accountId, SnapshotValue value) {
        validate(value);
        AccountSnapshot snapshot = snapshotRepository.findByAccountIdAndSnapshotDate(accountId, value.date())
                .orElseGet(AccountSnapshot::new);
        snapshot.setAccountId(accountId);
        snapshot.setSnapshotDate(value.date());
        snapshot.setBalance(scale(value.balance()));
        if (value.inflow() != null) {
            snapshot.setInflow(scale(value.inflow().abs()));
        }
        if (value.outflow() != null) {
            snapshot.setOutflow(scale(value.outflow().abs()));
        }
        if (value.investedCapital() != null) {
            snapshot.setInvestedCapital(scale(value.investedCapital()));
        }
        if (value.notes() != null && !value.notes().isBlank()) {
            snapshot.setNotes(value.notes().trim());
        }
        return snapshotRepository.save(snapshot);
    }

    private Account requireBalanceAccount(UUID accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("Account is required");
        }
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));
        if (account.getAccountType() == AccountType.INSTITUTION) {
            throw new IllegalArgumentException("Institutions carry no own balance");
        }
        return account;
    }

    private static void validate(SnapshotValue value) {
        if (value == null || value.date() == null) {
            throw new IllegalArgumentException("Snapshot date is required");
        }
        if (value.balance() == null) {
            throw new IllegalArgumentException("Balance is required");
        }
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
