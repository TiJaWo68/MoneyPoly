package de.in.mp.ui.finance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.in.mp.finance.Account;
import de.in.mp.finance.AccountSnapshot;
import de.in.mp.finance.AccountType;
import de.in.mp.finance.BalanceDateRow;

class BalanceChartPanelTest {

    @Test
    void buildsChronologicalChartPointsAggregatedByAccountType() {
        Account checking = account(AccountType.CHECKING, true);
        Account savings = account(AccountType.SAVINGS, true);
        Account investment = account(AccountType.INVESTMENT, true);

        LocalDate older = LocalDate.of(2020, 1, 1);
        LocalDate newer = LocalDate.of(2025, 1, 1);

        BalanceDateRow newerRow = new BalanceDateRow(newer, Map.of(
                checking.getId(), snapshot(checking.getId(), newer, "2000.00"),
                savings.getId(), snapshot(savings.getId(), newer, "10000.00"),
                investment.getId(), snapshot(investment.getId(), newer, "30000.00")));
        BalanceDateRow olderRow = new BalanceDateRow(older, Map.of(
                checking.getId(), snapshot(checking.getId(), older, "1000.00"),
                savings.getId(), snapshot(savings.getId(), older, "5000.00"),
                investment.getId(), snapshot(investment.getId(), older, "4000.00")));

        List<BalanceChartPoint> points = BalanceChartPoint.fromRows(
                List.of(checking, savings, investment),
                List.of(newerRow, olderRow));

        assertThat(points).hasSize(2);
        assertThat(points.get(0).date()).isEqualTo(older);
        assertThat(points.get(0).checking()).isEqualByComparingTo("1000.00");
        assertThat(points.get(0).savings()).isEqualByComparingTo("5000.00");
        assertThat(points.get(0).investment()).isEqualByComparingTo("4000.00");
        assertThat(points.get(0).total()).isEqualByComparingTo("10000.00");

        assertThat(points.get(1).date()).isEqualTo(newer);
        assertThat(points.get(1).total()).isEqualByComparingTo("42000.00");
    }

    @Test
    void carriesForwardMissingAccountSnapshotsOnIncompleteDatesWithoutRevivingClosedAccounts() {
        Account closedHistorical = account(AccountType.SAVINGS, false);
        Account activeDepot = account(AccountType.INVESTMENT, true);
        Account activeChecking = account(AccountType.CHECKING, true);

        LocalDate d1 = LocalDate.of(2020, 12, 31);
        LocalDate d2 = LocalDate.of(2025, 1, 31);
        LocalDate d3Partial = LocalDate.of(2025, 2, 15);
        LocalDate d4 = LocalDate.of(2025, 2, 28);

        BalanceDateRow r1 = new BalanceDateRow(d1, Map.of(
                closedHistorical.getId(), snapshot(closedHistorical.getId(), d1, "20000.00")));
        BalanceDateRow r2 = new BalanceDateRow(d2, Map.of(
                activeDepot.getId(), snapshot(activeDepot.getId(), d2, "100000.00"),
                activeChecking.getId(), snapshot(activeChecking.getId(), d2, "5000.00")));
        // On d3Partial only activeChecking had a statement imported; activeDepot must carry forward 100000.00
        BalanceDateRow r3 = new BalanceDateRow(d3Partial, Map.of(
                activeChecking.getId(), snapshot(activeChecking.getId(), d3Partial, "6000.00")));
        BalanceDateRow r4 = new BalanceDateRow(d4, Map.of(
                activeDepot.getId(), snapshot(activeDepot.getId(), d4, "110000.00"),
                activeChecking.getId(), snapshot(activeChecking.getId(), d4, "6500.00")));

        List<BalanceChartPoint> points = BalanceChartPoint.fromRows(
                List.of(closedHistorical, activeDepot, activeChecking),
                List.of(r4, r3, r2, r1));

        assertThat(points).hasSize(4);
        // At d1 (2020): closedHistorical has 20,000; newer accounts did not exist yet
        assertThat(points.get(0).total()).isEqualByComparingTo("20000.00");
        // At d2 (2025-01-31): closedHistorical is inactive and past its last snapshot (0), active total = 105,000
        assertThat(points.get(1).total()).isEqualByComparingTo("105000.00");
        // At d3Partial (2025-02-15): activeDepot carries forward 100,000 + activeChecking 6,000 = 106,000 (no gap!)
        assertThat(points.get(2).investment()).isEqualByComparingTo("100000.00");
        assertThat(points.get(2).checking()).isEqualByComparingTo("6000.00");
        assertThat(points.get(2).total()).isEqualByComparingTo("106000.00");
        // At d4 (2025-02-28): 110,000 + 6,500 = 116,500
        assertThat(points.get(3).total()).isEqualByComparingTo("116500.00");
    }

    @Test
    void filtersVisiblePointsByYearsPresetAndRangeSlidersAndRendersSvgPaths() {
        Account checking = account(AccountType.CHECKING, true);
        Account savings = account(AccountType.SAVINGS, true);
        Account investment = account(AccountType.INVESTMENT, true);
        List<Account> accounts = List.of(checking, savings, investment);

        LocalDate d2014 = LocalDate.of(2014, 6, 1);
        LocalDate d2020 = LocalDate.of(2020, 6, 1);
        LocalDate d2024 = LocalDate.of(2024, 6, 1);
        LocalDate d2026 = LocalDate.of(2026, 6, 1);

        List<BalanceDateRow> rows = List.of(
                row(d2026, checking, "3000", savings, "15000", investment, "50000"),
                row(d2024, checking, "2500", savings, "12000", investment, "35000"),
                row(d2020, checking, "2000", savings, "8000", investment, "15000"),
                row(d2014, checking, "1000", savings, "3000", investment, "2000"));

        BalanceChartPanel panel = new BalanceChartPanel();
        panel.setData(accounts, rows);

        assertThat(panel.visiblePointsForTest()).extracting(BalanceChartPoint::date)
                .containsExactly(d2014, d2020, d2024, d2026);
        assertThat(panel.svgMarkupForTest())
                .contains("<svg")
                .contains("data-series=\"CHECKING\"")
                .contains("data-series=\"SAVINGS\"")
                .contains("data-series=\"INVESTMENT\"")
                .contains("data-series=\"TOTAL\"");

        panel.selectYearsPreset(5);
        assertThat(panel.visiblePointsForTest()).extracting(BalanceChartPoint::date)
                .containsExactly(d2024, d2026);

        panel.selectYearsPreset(10);
        assertThat(panel.visiblePointsForTest()).extracting(BalanceChartPoint::date)
                .containsExactly(d2020, d2024, d2026);

        panel.selectMaxPreset();
        assertThat(panel.visiblePointsForTest()).hasSize(4);

        panel.setRangeIndices(1, 2);
        assertThat(panel.visiblePointsForTest()).extracting(BalanceChartPoint::date)
                .containsExactly(d2020, d2024);
    }

    private static Account account(AccountType type, boolean active) {
        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setName(type.name());
        account.setAccountType(type);
        account.setActive(active);
        return account;
    }

    private static BalanceDateRow row(LocalDate date,
            Account a1, String v1,
            Account a2, String v2,
            Account a3, String v3) {
        return new BalanceDateRow(date, Map.of(
                a1.getId(), snapshot(a1.getId(), date, v1),
                a2.getId(), snapshot(a2.getId(), date, v2),
                a3.getId(), snapshot(a3.getId(), date, v3)));
    }

    private static AccountSnapshot snapshot(UUID accountId, LocalDate date, String balance) {
        AccountSnapshot snapshot = new AccountSnapshot();
        snapshot.setAccountId(accountId);
        snapshot.setSnapshotDate(date);
        snapshot.setBalance(new BigDecimal(balance));
        return snapshot;
    }
}
