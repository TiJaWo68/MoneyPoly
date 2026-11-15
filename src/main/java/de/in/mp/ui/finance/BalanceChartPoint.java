package de.in.mp.ui.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import de.in.mp.finance.Account;
import de.in.mp.finance.AccountSnapshot;
import de.in.mp.finance.AccountType;
import de.in.mp.finance.BalanceDateRow;
import de.in.mp.finance.DepotValuation;

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
        BigDecimal total,
        BigDecimal investedCapital) {

    BalanceChartPoint(
            LocalDate date,
            BigDecimal checking,
            BigDecimal savings,
            BigDecimal investment,
            BigDecimal total) {
        this(date, checking, savings, investment, total, BigDecimal.ZERO);
    }

    BigDecimal stackedSavings() {
        return checking.add(savings);
    }

    BigDecimal stackedInvestment() {
        return checking.add(savings).add(investment);
    }

    static List<BalanceChartPoint> fromRows(List<Account> accounts, List<BalanceDateRow> rows) {
        return fromRows(accounts, rows, Map.of(), LocalDate.now());
    }

    static List<BalanceChartPoint> fromRows(
            List<Account> accounts,
            List<BalanceDateRow> rows,
            Map<UUID, DepotValuation> liveDepots) {
        return fromRows(accounts, rows, liveDepots, LocalDate.now());
    }

    static List<BalanceChartPoint> fromRows(
            List<Account> accounts,
            List<BalanceDateRow> rows,
            Map<UUID, DepotValuation> liveDepots,
            LocalDate today) {
        if (accounts == null || accounts.isEmpty()) {
            return List.of();
        }
        List<Account> balanceAccounts = accounts.stream()
                .filter(account -> account.getAccountType() != AccountType.INSTITUTION)
                .toList();
        Set<UUID> activeAccountIds = balanceAccounts.stream()
                .filter(Account::isActive)
                .map(Account::getId)
                .collect(Collectors.toSet());
        Map<UUID, DepotValuation> liveModeADepots = liveDepots == null ? Map.of()
                : liveDepots.entrySet().stream()
                        .filter(e -> e.getKey() != null && e.getValue() != null
                                && e.getValue().positionBased() && activeAccountIds.contains(e.getKey()))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        if ((rows == null || rows.isEmpty()) && liveModeADepots.isEmpty()) {
            return List.of();
        }

        List<BalanceDateRow> chronological = rows == null ? List.of()
                : rows.stream().sorted(Comparator.comparing(BalanceDateRow::date)).toList();
        Map<LocalDate, BalanceDateRow> rowsByDate = new HashMap<>();
        Set<LocalDate> dates = new TreeSet<>();
        for (BalanceDateRow row : chronological) {
            rowsByDate.put(row.date(), row);
            dates.add(row.date());
        }
        if (!liveModeADepots.isEmpty() && today != null) {
            dates.add(today);
        }

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
        if (!liveModeADepots.isEmpty() && today != null) {
            for (UUID depotId : liveModeADepots.keySet()) {
                firstSnapshotDate.putIfAbsent(depotId, today);
                LocalDate prevLast = lastSnapshotDate.get(depotId);
                if (prevLast == null || today.isAfter(prevLast)) {
                    lastSnapshotDate.put(depotId, today);
                }
            }
        }

        Map<UUID, BigDecimal> lastKnownBalance = new HashMap<>();
        Map<UUID, BigDecimal> lastKnownInvestedCapital = new HashMap<>();
        List<BalanceChartPoint> points = new ArrayList<>(dates.size());
        for (LocalDate date : dates) {
            BalanceDateRow row = rowsByDate.get(date);
            if (row != null) {
                for (Account account : balanceAccounts) {
                    AccountSnapshot snap = row.snapshotFor(account.getId());
                    if (snap != null && snap.getBalance() != null) {
                        lastKnownBalance.put(account.getId(), snap.getBalance());
                        if (snap.getInvestedCapital() != null) {
                            lastKnownInvestedCapital.put(account.getId(), snap.getInvestedCapital());
                        }
                    }
                }
            }
            if (!liveModeADepots.isEmpty() && date.equals(today)) {
                for (Map.Entry<UUID, DepotValuation> entry : liveModeADepots.entrySet()) {
                    DepotValuation val = entry.getValue();
                    if (val.currentValue() != null) {
                        lastKnownBalance.put(entry.getKey(), val.currentValue());
                    }
                    if (val.investedCapital() != null) {
                        lastKnownInvestedCapital.put(entry.getKey(), val.investedCapital());
                    }
                }
            }
            points.add(aggregateAtDate(balanceAccounts, date, firstSnapshotDate, lastSnapshotDate,
                    lastKnownBalance, lastKnownInvestedCapital));
        }
        return points;
    }

    private static BalanceChartPoint aggregateAtDate(
            List<Account> accounts,
            LocalDate date,
            Map<UUID, LocalDate> firstSnapshotDate,
            Map<UUID, LocalDate> lastSnapshotDate,
            Map<UUID, BigDecimal> lastKnownBalance,
            Map<UUID, BigDecimal> lastKnownInvestedCapital) {
        BigDecimal checking = BigDecimal.ZERO;
        BigDecimal savings = BigDecimal.ZERO;
        BigDecimal investment = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal investedCapital = BigDecimal.ZERO;

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
                investedCapital = investedCapital.add(lastKnownInvestedCapital.getOrDefault(id, BigDecimal.ZERO));
            } else if (type == AccountType.LOAN) {
                total = total.subtract(balance);
            }
        }
        return new BalanceChartPoint(date, checking, savings, investment, total, investedCapital);
    }
}
