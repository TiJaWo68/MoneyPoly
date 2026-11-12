package de.in.mp.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;

import de.in.mp.energy.EnergyAmortizationService;
import de.in.mp.energy.EnergyAssetService;
import de.in.mp.energy.EnergyTariffService;
import de.in.mp.energy.MeterReadingService;
import de.in.mp.energy.MeterService;
import de.in.mp.finance.AccountService;
import de.in.mp.finance.AccountSnapshotService;
import de.in.mp.finance.BankLookupService;
import de.in.mp.importer.StatementImportService;
import de.in.mp.ui.energy.EnergyView;
import de.in.mp.ui.finance.FinanceView;

class MobileResponsivenessTest {

    private static final int SMARTPHONE_VIEWPORT_WIDTH = 412; // Pixel 8 Pro logical width

    @Test
    void financeViewExpandsTabContentAndEnsuresMinimumGridHeightOnMobileScreens() {
        AccountService accountService = mock(AccountService.class);
        AccountSnapshotService snapshotService = mock(AccountSnapshotService.class);
        BankLookupService bankLookupService = mock(BankLookupService.class);
        StatementImportService statementImportService = mock(StatementImportService.class);

        when(accountService.findAll()).thenReturn(List.of());
        when(accountService.calculateNetWorth()).thenReturn(BigDecimal.ZERO);
        when(accountService.countActiveAccounts()).thenReturn(0L);
        when(snapshotService.buildDateRows()).thenReturn(List.of());

        FinanceView view = new FinanceView(accountService, snapshotService, bankLookupService, statementImportService);

        // 1. KPI cards must wrap on mobile viewports (< 450px)
        HorizontalLayout kpiRow = findChild(view, HorizontalLayout.class, 1);
        assertThat(kpiRow.getStyle().get("flex-wrap"))
                .as("Finance KPI row must have flex-wrap: wrap for smartphone displays")
                .isEqualTo("wrap");

        for (Component child : kpiRow.getChildren().toList()) {
            assertThat(child).isInstanceOf(VerticalLayout.class);
            VerticalLayout card = (VerticalLayout) child;
            assertThat(card.getStyle().get("min-width"))
                    .as("KPI card min-width must fit well within smartphone viewport width %d", SMARTPHONE_VIEWPORT_WIDTH)
                    .matches("^(1[0-7][0-9]px|[1-9][0-9]px)$");
        }

        // 2. tabContent must expand to take all available vertical space
        VerticalLayout tabContent = findChild(view, VerticalLayout.class, 0);
        assertThat(view.getFlexGrow(tabContent))
                .as("FinanceView must expand tabContent vertically")
                .isEqualTo(1.0);

        // 3. Switch to Balances tab and verify balanceGrid has expand and min-height >= 350px
        Tabs tabBar = findChild(view, Tabs.class, 0);
        Tab balancesTab = (Tab) tabBar.getChildren().toList().get(1);
        tabBar.setSelectedTab(balancesTab);

        Grid<?> balanceGrid = findChild(tabContent, Grid.class, 0);
        assertThat(tabContent.getFlexGrow(balanceGrid))
                .as("tabContent must expand balanceGrid")
                .isEqualTo(1.0);
        assertThat(parsePixelDimension(balanceGrid.getStyle().get("min-height")))
                .as("balanceGrid must have min-height >= 350px so it never collapses to a single row")
                .isGreaterThanOrEqualTo(350);
    }

    @Test
    void energyViewExpandsTabContentAndEnsuresMinimumGridHeightOnMobileScreens() {
        EnergyAssetService assetService = mock(EnergyAssetService.class);
        MeterService meterService = mock(MeterService.class);
        MeterReadingService readingService = mock(MeterReadingService.class);
        EnergyTariffService tariffService = mock(EnergyTariffService.class);
        EnergyAmortizationService amortizationService = mock(EnergyAmortizationService.class);

        when(assetService.findAll()).thenReturn(List.of());
        when(meterService.findActive()).thenReturn(List.of());
        when(meterService.findAll()).thenReturn(List.of());
        when(tariffService.findAll()).thenReturn(List.of());
        when(amortizationService.buildDateRows()).thenReturn(List.of());
        when(amortizationService.calculateTotalInvestmentCost()).thenReturn(BigDecimal.ZERO);
        when(amortizationService.calculateTotalAmortized()).thenReturn(BigDecimal.ZERO);
        when(amortizationService.calculateTotalRemainingCost()).thenReturn(BigDecimal.ZERO);

        EnergyView view = new EnergyView(assetService, meterService, readingService, tariffService, amortizationService);

        // 1. KPI cards must wrap on mobile viewports (< 450px)
        HorizontalLayout kpiRow = findChild(view, HorizontalLayout.class, 1);
        assertThat(kpiRow.getStyle().get("flex-wrap"))
                .as("Energy KPI row must have flex-wrap: wrap for smartphone displays")
                .isEqualTo("wrap");

        for (Component child : kpiRow.getChildren().toList()) {
            assertThat(child).isInstanceOf(VerticalLayout.class);
            VerticalLayout card = (VerticalLayout) child;
            assertThat(card.getStyle().get("min-width"))
                    .as("KPI card min-width must fit well within smartphone viewport width %d", SMARTPHONE_VIEWPORT_WIDTH)
                    .matches("^(1[0-7][0-9]px|[1-9][0-9]px)$");
        }

        // 2. tabContent must expand to take all available vertical space
        VerticalLayout tabContent = findChild(view, VerticalLayout.class, 0);
        assertThat(view.getFlexGrow(tabContent))
                .as("EnergyView must expand tabContent vertically")
                .isEqualTo(1.0);

        // 3. On Readings tab, readingsGrid must have expand and min-height >= 350px
        Grid<?> readingsGrid = findChild(tabContent, Grid.class, 0);
        assertThat(tabContent.getFlexGrow(readingsGrid))
                .as("tabContent must expand readingsGrid")
                .isEqualTo(1.0);
        assertThat(parsePixelDimension(readingsGrid.getStyle().get("min-height")))
                .as("readingsGrid must have min-height >= 350px so it never collapses to a single row")
                .isGreaterThanOrEqualTo(350);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Component> T findChild(Component parent, Class<T> type, int index) {
        List<Component> matches = parent.getChildren().filter(type::isInstance).toList();
        assertThat(matches.size())
                .as("Parent %s should have child of type %s at index %d", parent.getClass().getSimpleName(), type.getSimpleName(), index)
                .isGreaterThan(index);
        return (T) matches.get(index);
    }

    private static int parsePixelDimension(String value) {
        if (value == null || !value.endsWith("px")) {
            return 0;
        }
        try {
            return Integer.parseInt(value.replace("px", "").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
