package de.in.mp.ui.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import de.in.mp.finance.Account;
import de.in.mp.finance.AccountSnapshot;
import de.in.mp.finance.AccountType;
import de.in.mp.finance.BalanceDateRow;

/**
 * Aggregated balance snapshot for a single reference date on the finance chart,
 * split by account type and ordered chronologically.
 * <p>
 * Missing single-account snapshots on intermediate dates carry forward the most
 * recent balance of each active or ongoing account so partial statement imports
 * do not cause artificial drops. Closed ({@code inactive}) accounts contribute
 * up to their last recorded snapshot and drop to zero afterwards.
 */
record BalanceChartPoint(
        LocalDate date,
        BigDecimal checking,
        BigDecimal savings,
        BigDecimal investment,
        BigDecimal total) {

    BigDecimal stackedSavings() {
        return checking.add(savings);
    }

    BigDecimal stackedInvestment() {
        return checking.add(savings).add(investment);
    }

    static List<BalanceChartPoint> fromRows(List<Account> accounts, List<BalanceDateRow> rows) {
        if (rows.isEmpty() || accounts.isEmpty()) {
            return List.of();
        }
        List<Account> balanceAccounts = accounts.stream()
                .filter(account -> account.getAccountType() != AccountType.INSTITUTION)
                .toList();
        List<BalanceDateRow> chronological = rows.stream()
                .sorted(Comparator.comparing(BalanceDateRow::date))
                .toList();

        Map<UUID, LocalDate> firstSnapshotDate = new HashMap<>();
        Map<UUID, LocalDate> lastSnapshotDate = new HashMap<>();
        for (BalanceDateRow row : chronological) {
            for (Account account : balanceAccounts) {
                AccountSnapshot snap = row.snapshotFor(account.getId());
                if (snap != null && snap.getBalance() != null) {
                    firstSnapshotDate.putIfAbsent(account.getId(), row.date());
                    lastSnapshotDate.put(account.getId(), row.date());
                }
            }
        }

        Map<UUID, BigDecimal> lastKnownBalance = new HashMap<>();
        List<BalanceChartPoint> points = new ArrayList<>(chronological.size());
        for (BalanceDateRow row : chronological) {
            LocalDate date = row.date();
            for (Account account : balanceAccounts) {
                AccountSnapshot snap = row.snapshotFor(account.getId());
                if (snap != null && snap.getBalance() != null) {
                    lastKnownBalance.put(account.getId(), snap.getBalance());
                }
            }
            points.add(aggregateAtDate(balanceAccounts, date, firstSnapshotDate, lastSnapshotDate, lastKnownBalance));
        }
        return points;
    }

    private static BalanceChartPoint aggregateAtDate(
            List<Account> accounts,
            LocalDate date,
            Map<UUID, LocalDate> firstSnapshotDate,
            Map<UUID, LocalDate> lastSnapshotDate,
            Map<UUID, BigDecimal> lastKnownBalance) {
        BigDecimal checking = BigDecimal.ZERO;
        BigDecimal savings = BigDecimal.ZERO;
        BigDecimal investment = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;

        for (Account account : accounts) {
            UUID id = account.getId();
            LocalDate firstDate = firstSnapshotDate.get(id);
            LocalDate lastDate = lastSnapshotDate.get(id);
            if (firstDate == null || date.isBefore(firstDate)) {
                continue;
            }
            if (lastDate != null && date.isAfter(lastDate) && !account.isActive()) {
                continue;
            }
            BigDecimal balance = lastKnownBalance.getOrDefault(id, BigDecimal.ZERO);
            AccountType type = account.getAccountType();
            if (type == AccountType.CHECKING) {
                checking = checking.add(balance);
                total = total.add(balance);
            } else if (type == AccountType.SAVINGS) {
                savings = savings.add(balance);
                total = total.add(balance);
            } else if (type == AccountType.INVESTMENT) {
                investment = investment.add(balance);
                total = total.add(balance);
            } else if (type == AccountType.LOAN) {
                total = total.subtract(balance);
            }
        }
        return new BalanceChartPoint(date, checking, savings, investment, total);
    }
}
