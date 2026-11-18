package de.in.mp.ui.finance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.in.mp.finance.Account;
import de.in.mp.finance.AccountSnapshot;
import de.in.mp.finance.AccountType;
import de.in.mp.finance.BalanceDateRow;
import de.in.mp.finance.DepotValuation;
import de.in.mp.finance.WealthEvent;

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
        assertThat(panel.svgMarkupForTest()).doesNotContain("data-series=\"INVESTED_CAPITAL\"");
    }

    @Test
    void rendersInvestedCapitalLineWhenInvestmentSnapshotsContainCostBasis() {
        Account investment = account(AccountType.INVESTMENT, true);
        LocalDate d1 = LocalDate.of(2024, 1, 1);
        LocalDate d2 = LocalDate.of(2025, 1, 1);

        AccountSnapshot s1 = snapshot(investment.getId(), d1, "20000.00");
        s1.setInvestedCapital(new BigDecimal("18000.00"));
        AccountSnapshot s2 = snapshot(investment.getId(), d2, "25000.00");
        s2.setInvestedCapital(new BigDecimal("21000.00"));

        BalanceDateRow r1 = new BalanceDateRow(d1, Map.of(investment.getId(), s1));
        BalanceDateRow r2 = new BalanceDateRow(d2, Map.of(investment.getId(), s2));

        BalanceChartPanel panel = new BalanceChartPanel();
        panel.setData(List.of(investment), List.of(r2, r1));

        assertThat(panel.visiblePointsForTest())
                .extracting(BalanceChartPoint::investedCapital)
                .containsExactly(new BigDecimal("18000.00"), new BigDecimal("21000.00"));
        assertThat(panel.svgMarkupForTest()).contains("data-series=\"INVESTED_CAPITAL\"");
    }

    @Test
    void appendsTodayPointForLiveModeADepotWithoutPersistedSnapshot() {
        Account checking = account(AccountType.CHECKING, true);
        Account modeADepot = account(AccountType.INVESTMENT, true);

        LocalDate past = LocalDate.of(2026, 10, 31);
        LocalDate today = LocalDate.of(2026, 11, 15);

        BalanceDateRow historicalRow = new BalanceDateRow(past, Map.of(
                checking.getId(), snapshot(checking.getId(), past, "5000.00"),
                modeADepot.getId(), snapshot(modeADepot.getId(), past, "20000.00")));
        DepotValuation liveDepot = new DepotValuation(
                modeADepot.getId(), true,
                new BigDecimal("22500.00"), new BigDecimal("18000.00"),
                new BigDecimal("22500.00"), true, 2);

        List<BalanceChartPoint> points = BalanceChartPoint.fromRows(
                List.of(checking, modeADepot),
                List.of(historicalRow),
                Map.of(modeADepot.getId(), liveDepot),
                today);

        assertThat(points).hasSize(2);
        assertThat(points.get(0).date()).isEqualTo(past);
        assertThat(points.get(0).total()).isEqualByComparingTo("25000.00");

        assertThat(points.get(1).date()).isEqualTo(today);
        assertThat(points.get(1).checking()).isEqualByComparingTo("5000.00");
        assertThat(points.get(1).investment()).isEqualByComparingTo("22500.00");
        assertThat(points.get(1).investedCapital()).isEqualByComparingTo("18000.00");
        assertThat(points.get(1).total()).isEqualByComparingTo("27500.00");
    }

    @Test
    void rendersWealthEventMarkersClickableSegmentsAndTooltipDescriptionsWithoutLumpingOldEventsOnZoom() {
        Account checking = account(AccountType.CHECKING, true);
        Account savings = account(AccountType.SAVINGS, true);
        Account investment = account(AccountType.INVESTMENT, true);
        List<Account> accounts = List.of(checking, savings, investment);

        LocalDate d1 = LocalDate.of(2023, 1, 31);
        LocalDate d2 = LocalDate.of(2024, 6, 30);
        LocalDate d3 = LocalDate.of(2025, 1, 31);

        List<BalanceDateRow> rows = List.of(
                row(d3, checking, "2000", savings, "10000", investment, "30000"),
                row(d2, checking, "3000", savings, "15000", investment, "32000"),
                row(d1, checking, "4000", savings, "12000", investment, "28000"));

        WealthEvent oldEvent = new WealthEvent();
        oldEvent.setId(UUID.randomUUID());
        oldEvent.setEventDate(LocalDate.of(2023, 1, 15));
        oldEvent.setAmount(new BigDecimal("-4500.00"));
        oldEvent.setDescription("Historical old event");

        WealthEvent intervalEvent = new WealthEvent();
        intervalEvent.setId(UUID.randomUUID());
        intervalEvent.setEventDate(LocalDate.of(2024, 10, 15));
        intervalEvent.setAmount(new BigDecimal("-8000.00"));
        intervalEvent.setDescription("Loan Gamma");

        BalanceChartPanel panel = new BalanceChartPanel();
        panel.setData(accounts, rows, Map.of(), List.of(oldEvent, intervalEvent));

        assertThat(panel.svgMarkupForTest())
                .contains("class=\"mp-seg\"")
                .contains("class=\"mp-ev\"")
                .contains("Historical old event")
                .contains("Loan Gamma");

        // Zoom to [d2, d3] (indices 1..2): prev point for d2 is d1 (in allPoints),
        // so Historical old event (before d1) must NOT leak onto d2!
        panel.setRangeIndices(1, 2);
        assertThat(panel.svgMarkupForTest())
                .contains("Loan Gamma")
                .doesNotContain("Historical old event");
    }

    @Test
    void invokesIntervalDoubleClickCallbackWithPreviousPointDateAndDeltaEvenWhenZoomed() {
        Account checking = account(AccountType.CHECKING, true);
        Account savings = account(AccountType.SAVINGS, true);
        Account investment = account(AccountType.INVESTMENT, true);
        List<Account> accounts = List.of(checking, savings, investment);

        LocalDate d1 = LocalDate.of(2024, 1, 31);
        LocalDate d2 = LocalDate.of(2024, 6, 30);
        LocalDate d3 = LocalDate.of(2024, 12, 31);

        List<BalanceDateRow> rows = List.of(
                row(d3, checking, "1000", savings, "5000", investment, "10000"), // total 16000
                row(d2, checking, "2000", savings, "8000", investment, "10000"), // total 20000
                row(d1, checking, "5000", savings, "10000", investment, "10000")); // total 25000

        BalanceChartPanel panel = new BalanceChartPanel();
        List<BalanceChartPanel.ChartInterval> captured = new ArrayList<>();
        panel.setChartInteractionCallbacks(captured::add, () -> {
        });
        panel.setData(accounts, rows, Map.of(), List.of());

        panel.setRangeIndices(1, 2);
        // visible index 0 is d2 -> previous point in allPoints is d1 -> delta = 20000 - 25000 = -5000
        panel.handleChartIntervalDoubleClick(0);
        // visible index 1 is d3 -> previous point is d2 -> delta = 16000 - 20000 = -4000
        panel.handleChartIntervalDoubleClick(1);

        assertThat(captured).hasSize(2);
        assertThat(captured.get(0).previousDate()).isEqualTo(d1);
        assertThat(captured.get(0).targetDate()).isEqualTo(d2);
        assertThat(captured.get(0).netWorthDelta()).isEqualByComparingTo("-5000");

        assertThat(captured.get(1).previousDate()).isEqualTo(d2);
        assertThat(captured.get(1).targetDate()).isEqualTo(d3);
        assertThat(captured.get(1).netWorthDelta()).isEqualByComparingTo("-4000");
    }

    @Test
    void supportsMonthPresetsDragZoomWheelZoomPanAndPreservesZoomAcrossDataReloads() {
        Account checking = account(AccountType.CHECKING, true);
        Account savings = account(AccountType.SAVINGS, true);
        Account investment = account(AccountType.INVESTMENT, true);
        List<Account> accounts = List.of(checking, savings, investment);

        // 10 evenly spaced monthly points across 2025
        List<BalanceDateRow> rows = new ArrayList<>();
        for (int m = 1; m <= 10; m++) {
            rows.add(row(LocalDate.of(2025, m, 15), checking, "2000", savings, "10000", investment, String.valueOf(20000 + m * 1000)));
        }

        BalanceChartPanel panel = new BalanceChartPanel();
        panel.setData(accounts, rows);
        assertThat(panel.visiblePointsForTest()).hasSize(10);

        // Month preset 3M keeps last ~3 months from 2025-10-15 -> >= 2025-07-15 (months 7, 8, 9, 10)
        panel.selectMonthsPreset(3);
        assertThat(panel.visiblePointsForTest()).extracting(BalanceChartPoint::date)
                .containsExactly(
                        LocalDate.of(2025, 7, 15),
                        LocalDate.of(2025, 8, 15),
                        LocalDate.of(2025, 9, 15),
                        LocalDate.of(2025, 10, 15));

        // Reset to Max, then drag-box zoom over the middle 40%-75%
        panel.selectMaxPreset();
        panel.handlePlotRangeZoom(0.40, 0.75);
        List<BalanceChartPoint> zoomed = panel.visiblePointsForTest();
        assertThat(zoomed.size()).isBetween(2, 6);

        // Saving a wealth event reloads data via setData(...) - custom zoom must stay intact!
        LocalDate firstZoomDate = zoomed.get(0).date();
        LocalDate lastZoomDate = zoomed.get(zoomed.size() - 1).date();
        panel.setData(accounts, rows, Map.of(), List.of());
        assertThat(panel.visiblePointsForTest().get(0).date()).isEqualTo(firstZoomDate);
        assertThat(panel.visiblePointsForTest().get(panel.visiblePointsForTest().size() - 1).date())
                .isEqualTo(lastZoomDate);

        // Pan left & right shifts range while staying zoomed
        panel.panLeft();
        assertThat(panel.visiblePointsForTest().get(0).date()).isBefore(firstZoomDate);
        panel.panRight();
        assertThat(panel.visiblePointsForTest().get(0).date()).isEqualTo(firstZoomDate);

        // Wheel zoom out restores wider range
        int spanBeforeOut = panel.visiblePointsForTest().size();
        panel.handlePlotWheelZoom(0.5, false);
        assertThat(panel.visiblePointsForTest().size()).isGreaterThan(spanBeforeOut);

        // Tight drag inside a single segment expands safely to include both segment endpoints
        panel.selectMaxPreset();
        panel.handlePlotRangeZoom(0.02, 0.04);
        assertThat(panel.visiblePointsForTest()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void togglesFocusedYScaleAndEvenXSpacingForReadableDenseRanges() {
        Account checking = account(AccountType.CHECKING, true);
        Account savings = account(AccountType.SAVINGS, true);
        Account investment = account(AccountType.INVESTMENT, true);
        List<Account> accounts = List.of(checking, savings, investment);

        // Uneven dates (2020 gap, then dense days in 2026) with high base net worth (410k - 430k)
        List<BalanceDateRow> rows = List.of(
                row(LocalDate.of(2020, 1, 1), checking, "10000", savings, "50000", investment, "350000"),
                row(LocalDate.of(2026, 1, 10), checking, "10000", savings, "50000", investment, "365000"),
                row(LocalDate.of(2026, 1, 12), checking, "10000", savings, "50000", investment, "370000"));

        BalanceChartPanel panel = new BalanceChartPanel();
        panel.setData(accounts, rows);

        // Default: 0-based Y and TIME X
        assertThat(panel.svgMarkupForTest())
                .contains("data-ymin=\"0\"")
                .contains("data-xmode=\"TIME\"");

        // Enable Y Focus: data-ymin rises significantly above 0
        panel.setYFocusEnabled(true);
        assertThat(panel.isYFocusEnabled()).isTrue();
        assertThat(panel.svgMarkupForTest())
                .doesNotContain("data-ymin=\"0\"");

        // Enable Even X spacing: switches xmode and places middle point strictly in the center (x=740.0)
        panel.setEvenPointSpacing(true);
        assertThat(panel.isEvenPointSpacing()).isTrue();
        assertThat(panel.svgMarkupForTest())
                .contains("data-xmode=\"EVEN\"")
                .contains("cx=\"740.0\"");
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
