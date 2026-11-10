package de.in.mp.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains the balance history of finance accounts.
 * <p>
 * Snapshots are upserted per (account, date). After every change the
 * account's {@code currentBalance} is re-synchronized with its most recent
 * snapshot so that KPIs and the dashboard always reflect the latest known
 * statement balance.
 */
@Service
public class AccountSnapshotService {

    private static final int MONEY_SCALE = 2;

    private final AccountSnapshotRepository snapshotRepository;
    private final AccountRepository accountRepository;

    public AccountSnapshotService(AccountSnapshotRepository snapshotRepository,
            AccountRepository accountRepository) {
        this.snapshotRepository = snapshotRepository;
        this.accountRepository = accountRepository;
    }

    /**
     * Creates or updates the snapshot of an account at the given date.
     * {@code inflow}/{@code outflow}/{@code notes} are optional; {@code null}
     * keeps an already stored value untouched.
     */
    @Transactional
    public AccountSnapshot recordSnapshot(UUID accountId, SnapshotValue value) {
        Account account = requireBalanceAccount(accountId);
        AccountSnapshot saved = upsert(accountId, value);
        synchronizeCurrentBalance(account);
        return saved;
    }

    /**
     * Records many snapshots of one account (e.g. a historical migration) and
     * synchronizes the current balance only once.
     */
    @Transactional
    public int recordSnapshots(UUID accountId, List<SnapshotValue> values) {
        Account account = requireBalanceAccount(accountId);
        for (SnapshotValue value : values) {
            upsert(accountId, value);
        }
        synchronizeCurrentBalance(account);
        return values.size();
    }

    /**
     * Records the balances of several accounts for one reference date, e.g.
     * from the quick-entry dialog. Accounts mapped to {@code null} are skipped.
     */
    @Transactional
    public int recordBatch(LocalDate date, Map<UUID, BigDecimal> balancesByAccount) {
        int saved = 0;
        for (Map.Entry<UUID, BigDecimal> entry : balancesByAccount.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            recordSnapshot(entry.getKey(), SnapshotValue.ofBalance(date, entry.getValue()));
            saved++;
        }
        return saved;
    }

    /**
     * Removes all snapshots of a reference date (one row in the balance matrix).
     */
    @Transactional
    public void deleteSnapshotsForDate(LocalDate date) {
        List<AccountSnapshot> snapshots = snapshotRepository.findBySnapshotDate(date);
        snapshotRepository.deleteAll(snapshots);
        snapshots.stream()
                .map(AccountSnapshot::getAccountId)
                .distinct()
                .forEach(id -> accountRepository.findById(id).ifPresent(this::synchronizeCurrentBalance));
    }

    @Transactional(readOnly = true)
    public Optional<AccountSnapshot> findLatest(UUID accountId) {
        return snapshotRepository.findFirstByAccountIdOrderBySnapshotDateDesc(accountId);
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
        Map<LocalDate, Map<UUID, AccountSnapshot>> byDate = new TreeMap<>(Comparator.reverseOrder());
        for (AccountSnapshot snapshot : snapshotRepository.findAllByOrderBySnapshotDateDesc()) {
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
        if (value.notes() != null && !value.notes().isBlank()) {
            snapshot.setNotes(value.notes().trim());
        }
        return snapshotRepository.save(snapshot);
    }

    private void synchronizeCurrentBalance(Account account) {
        BigDecimal latest = snapshotRepository.findFirstByAccountIdOrderBySnapshotDateDesc(account.getId())
                .map(AccountSnapshot::getBalance)
                .orElse(account.getInitialBalance());
        account.setCurrentBalance(latest == null ? BigDecimal.ZERO : latest);
        accountRepository.save(account);
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
