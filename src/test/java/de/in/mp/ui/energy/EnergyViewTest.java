package de.in.mp.ui.energy;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import de.in.mp.energy.Meter;
import de.in.mp.energy.MeterReading;
import de.in.mp.energy.MeterType;

class EnergyViewTest {

    @Test
    void stripsGroupPrefixFromMeterShortHeader() {
        assertThat(EnergyView.shortColumnHeader(meter("Wasser Gesamt", MeterType.WATER))).isEqualTo("Gesamt");
        assertThat(EnergyView.shortColumnHeader(meter("Wasser HV", MeterType.WATER))).isEqualTo("HV");
        assertThat(EnergyView.shortColumnHeader(meter("Wasser HH", MeterType.WATER))).isEqualTo("HH");
        assertThat(EnergyView.shortColumnHeader(meter("Gas", MeterType.GAS))).isEqualTo("Gas");
        assertThat(EnergyView.shortColumnHeader(meter("Strom Gesamt", MeterType.GRID_IMPORT))).isEqualTo("Gesamt");
        assertThat(EnergyView.shortColumnHeader(meter("Strom Einspeisung", MeterType.GRID_EXPORT)))
                .isEqualTo("Einspeisung");
        assertThat(EnergyView.shortColumnHeader(meter("PV Gartenhaus", MeterType.PV_GENERATION)))
                .isEqualTo("Gartenhaus");
        assertThat(EnergyView.shortColumnHeader(meter("PV Dach", MeterType.PV_GENERATION))).isEqualTo("Dach");
        assertThat(EnergyView.shortColumnHeader(meter("Wallbox Laden", MeterType.WALLBOX_PRIVATE))).isEqualTo("Laden");
        assertThat(EnergyView.shortColumnHeader(meter("Wallbox weiß", MeterType.WALLBOX_COMPANY))).isEqualTo("weiß");
        assertThat(EnergyView.shortColumnHeader(meter("Wallbox ID4", MeterType.WALLBOX_COMPANY))).isEqualTo("ID4");
    }

    @Test
    void formatsMeterCellWithoutInlineDeltaAndBuildsTooltipWithAverageComparison() {
        MeterReading reading = new MeterReading();
        reading.setReadingValue(new BigDecimal("1593"));
        reading.setConsumptionDelta(new BigDecimal("18"));

        assertThat(EnergyView.formatMeterValueOnly(reading, Locale.GERMAN)).isEqualTo("1.593");

        MeterReading fractional = new MeterReading();
        fractional.setReadingValue(new BigDecimal("1583.708"));
        assertThat(EnergyView.formatMeterValueOnly(fractional, Locale.GERMAN)).isEqualTo("1.584");

        String comparison = EnergyView.formatAverageComparison(
                new BigDecimal("18"), new BigDecimal("15"), Locale.GERMAN);
        assertThat(comparison).isEqualTo("+20% vs. Ø");

        String belowComparison = EnergyView.formatAverageComparison(
                new BigDecimal("12"), new BigDecimal("15"), Locale.GERMAN);
        assertThat(belowComparison).isEqualTo("-20% vs. Ø");
    }

    @Test
    void actionMenuAdaptsItemsToSelectedTabAndTogglesKpis() {
        de.in.mp.energy.EnergyAssetService assetService = org.mockito.Mockito.mock(de.in.mp.energy.EnergyAssetService.class);
        de.in.mp.energy.MeterService meterService = org.mockito.Mockito.mock(de.in.mp.energy.MeterService.class);
        de.in.mp.energy.MeterReadingService readingService = org.mockito.Mockito.mock(de.in.mp.energy.MeterReadingService.class);
        de.in.mp.energy.EnergyTariffService tariffService = org.mockito.Mockito.mock(de.in.mp.energy.EnergyTariffService.class);
        de.in.mp.energy.EnergyAmortizationService amortizationService = org.mockito.Mockito.mock(de.in.mp.energy.EnergyAmortizationService.class);

        org.mockito.Mockito.when(assetService.findAll()).thenReturn(java.util.List.of());
        org.mockito.Mockito.when(meterService.findActive()).thenReturn(java.util.List.of());
        org.mockito.Mockito.when(meterService.findAll()).thenReturn(java.util.List.of());
        org.mockito.Mockito.when(tariffService.findAll()).thenReturn(java.util.List.of());
        org.mockito.Mockito.when(amortizationService.buildDateRows()).thenReturn(java.util.List.of());
        org.mockito.Mockito.when(amortizationService.calculateTotalInvestmentCost()).thenReturn(BigDecimal.ZERO);
        org.mockito.Mockito.when(amortizationService.calculateTotalAmortized()).thenReturn(BigDecimal.ZERO);
        org.mockito.Mockito.when(amortizationService.calculateTotalRemainingCost()).thenReturn(BigDecimal.ZERO);

        EnergyView view = new EnergyView(assetService, meterService, readingService, tariffService, amortizationService);
        com.vaadin.flow.component.tabs.Tabs tabBar = view.getChildren()
                .filter(com.vaadin.flow.component.tabs.Tabs.class::isInstance)
                .map(com.vaadin.flow.component.tabs.Tabs.class::cast)
                .findFirst()
                .orElseThrow();

        // Tab 0 (Readings): Add reading, Show KPIs
        assertThat(view.getActionMenuForTest().getSubMenu().getItems()).hasSize(2);

        // Tab 1 (Amortization): Add reading, New asset, Show KPIs
        tabBar.setSelectedTab((com.vaadin.flow.component.tabs.Tab) tabBar.getChildren().toList().get(1));
        assertThat(view.getActionMenuForTest().getSubMenu().getItems()).hasSize(3);

        // Tab 2 (Master data): Add reading, New meter, New tariff, Show KPIs
        tabBar.setSelectedTab((com.vaadin.flow.component.tabs.Tab) tabBar.getChildren().toList().get(2));
        java.util.List<com.vaadin.flow.component.contextmenu.MenuItem> masterItems = view.getActionMenuForTest().getSubMenu().getItems();
        assertThat(masterItems).hasSize(4);

        // Toggle KPIs off
        assertThat(view.getKpiRowForTest().isVisible()).isTrue();
        com.vaadin.flow.component.contextmenu.MenuItem kpiToggle = masterItems.get(masterItems.size() - 1);
        assertThat(kpiToggle.isCheckable()).isTrue();
        assertThat(kpiToggle.isChecked()).isTrue();
        kpiToggle.setChecked(false);
        com.vaadin.flow.component.ComponentUtil.fireEvent(kpiToggle, new com.vaadin.flow.component.ClickEvent<>(kpiToggle));
        assertThat(view.getKpiRowForTest().isVisible()).isFalse();
    }

    @Test
    void preservesReadingsGridColumnsAcrossRowRefreshWhenMetersAreUnchanged() {
        de.in.mp.energy.EnergyAssetService assetService = org.mockito.Mockito.mock(de.in.mp.energy.EnergyAssetService.class);
        de.in.mp.energy.MeterService meterService = org.mockito.Mockito.mock(de.in.mp.energy.MeterService.class);
        de.in.mp.energy.MeterReadingService readingService = org.mockito.Mockito.mock(de.in.mp.energy.MeterReadingService.class);
        de.in.mp.energy.EnergyTariffService tariffService = org.mockito.Mockito.mock(de.in.mp.energy.EnergyTariffService.class);
        de.in.mp.energy.EnergyAmortizationService amortizationService = org.mockito.Mockito.mock(de.in.mp.energy.EnergyAmortizationService.class);

        Meter water = meter("Wasser Gesamt", MeterType.WATER);
        water.setId(java.util.UUID.randomUUID());
        water.setUnit("m3");
        Meter pv = meter("PV Dach", MeterType.PV_GENERATION);
        pv.setId(java.util.UUID.randomUUID());
        pv.setUnit("kWh");

        org.mockito.Mockito.when(assetService.findAll()).thenReturn(java.util.List.of());
        org.mockito.Mockito.when(meterService.findActive()).thenReturn(java.util.List.of(water, pv));
        org.mockito.Mockito.when(meterService.findAll()).thenReturn(java.util.List.of(water, pv));
        org.mockito.Mockito.when(tariffService.findAll()).thenReturn(java.util.List.of());
        org.mockito.Mockito.when(amortizationService.buildDateRows()).thenReturn(java.util.List.of());
        org.mockito.Mockito.when(amortizationService.calculateTotalInvestmentCost()).thenReturn(BigDecimal.ZERO);
        org.mockito.Mockito.when(amortizationService.calculateTotalAmortized()).thenReturn(BigDecimal.ZERO);
        org.mockito.Mockito.when(amortizationService.calculateTotalRemainingCost()).thenReturn(BigDecimal.ZERO);

        EnergyView view = new EnergyView(assetService, meterService, readingService, tariffService, amortizationService);
        var initialColumns = java.util.List.copyOf(view.getReadingsGridForTest().getColumns());

        view.refreshForTest();

        assertThat(view.getReadingsGridForTest().getColumns()).containsExactlyElementsOf(initialColumns);
    }

    @Test
    void placesActionMenuDirectlyRightOfTitleWithoutExpandingTitle() {
        de.in.mp.energy.EnergyAssetService assetService = org.mockito.Mockito.mock(de.in.mp.energy.EnergyAssetService.class);
        de.in.mp.energy.MeterService meterService = org.mockito.Mockito.mock(de.in.mp.energy.MeterService.class);
        de.in.mp.energy.MeterReadingService readingService = org.mockito.Mockito.mock(de.in.mp.energy.MeterReadingService.class);
        de.in.mp.energy.EnergyTariffService tariffService = org.mockito.Mockito.mock(de.in.mp.energy.EnergyTariffService.class);
        de.in.mp.energy.EnergyAmortizationService amortizationService = org.mockito.Mockito.mock(de.in.mp.energy.EnergyAmortizationService.class);

        org.mockito.Mockito.when(assetService.findAll()).thenReturn(java.util.List.of());
        org.mockito.Mockito.when(meterService.findActive()).thenReturn(java.util.List.of());
        org.mockito.Mockito.when(meterService.findAll()).thenReturn(java.util.List.of());
        org.mockito.Mockito.when(tariffService.findAll()).thenReturn(java.util.List.of());
        org.mockito.Mockito.when(amortizationService.buildDateRows()).thenReturn(java.util.List.of());
        org.mockito.Mockito.when(amortizationService.calculateTotalInvestmentCost()).thenReturn(BigDecimal.ZERO);
        org.mockito.Mockito.when(amortizationService.calculateTotalAmortized()).thenReturn(BigDecimal.ZERO);
        org.mockito.Mockito.when(amortizationService.calculateTotalRemainingCost()).thenReturn(BigDecimal.ZERO);

        EnergyView view = new EnergyView(assetService, meterService, readingService, tariffService, amortizationService);

        var header = view.getChildren()
                .filter(com.vaadin.flow.component.orderedlayout.HorizontalLayout.class::isInstance)
                .map(com.vaadin.flow.component.orderedlayout.HorizontalLayout.class::cast)
                .findFirst()
                .orElseThrow();
        var headerChildren = header.getChildren().toList();
        assertThat(headerChildren).hasSize(2);
        assertThat(headerChildren.get(0)).isInstanceOf(com.vaadin.flow.component.html.H2.class);
        assertThat(headerChildren.get(1)).isSameAs(view.getActionMenuForTest().getMenuBar());
        assertThat(header.getFlexGrow(headerChildren.get(0))).isZero();
        assertThat(view.getActionMenuForTest().getMenuBar().getThemeNames())
                .doesNotContain("end-aligned");
    }

    @Test
    void showsHouseSelectorForMultipleHousesAndHidesWriteActionsForViewer() {
        var assetService = org.mockito.Mockito.mock(de.in.mp.energy.EnergyAssetService.class);
        var meterService = org.mockito.Mockito.mock(de.in.mp.energy.MeterService.class);
        var readingService = org.mockito.Mockito.mock(de.in.mp.energy.MeterReadingService.class);
        var tariffService = org.mockito.Mockito.mock(de.in.mp.energy.EnergyTariffService.class);
        var amortizationService = org.mockito.Mockito.mock(de.in.mp.energy.EnergyAmortizationService.class);
        var houseService = org.mockito.Mockito.mock(de.in.mp.energy.EnergyHouseService.class);

        var house1 = new de.in.mp.energy.EnergyHouse();
        house1.setId(java.util.UUID.randomUUID());
        house1.setName("Haus 1");
        var house2 = new de.in.mp.energy.EnergyHouse();
        house2.setId(java.util.UUID.randomUUID());
        house2.setName("Haus 2");

        org.mockito.Mockito.when(houseService.findAccessibleHousesForCurrentUser())
                .thenReturn(java.util.List.of(house1, house2));
        org.mockito.Mockito.when(houseService.canEditHouseForCurrentUser(house1.getId())).thenReturn(false);
        org.mockito.Mockito.when(assetService.findByHouse(house1.getId())).thenReturn(java.util.List.of());
        org.mockito.Mockito.when(meterService.findActiveByHouse(house1.getId())).thenReturn(java.util.List.of());
        org.mockito.Mockito.when(meterService.findByHouse(house1.getId())).thenReturn(java.util.List.of());
        org.mockito.Mockito.when(tariffService.findByHouse(house1.getId())).thenReturn(java.util.List.of());
        org.mockito.Mockito.when(amortizationService.buildDateRows(house1.getId())).thenReturn(java.util.List.of());
        org.mockito.Mockito.when(amortizationService.calculateAssetAmortizations(house1.getId())).thenReturn(java.util.List.of());
        org.mockito.Mockito.when(amortizationService.calculateTotalInvestmentCost(house1.getId())).thenReturn(BigDecimal.ZERO);
        org.mockito.Mockito.when(amortizationService.calculateTotalAmortized(house1.getId())).thenReturn(BigDecimal.ZERO);
        org.mockito.Mockito.when(amortizationService.calculateTotalRemainingCost(house1.getId())).thenReturn(BigDecimal.ZERO);

        EnergyView view = new EnergyView(assetService, meterService, readingService,
                tariffService, amortizationService, null, null, houseService);

        var header = view.getChildren()
                .filter(com.vaadin.flow.component.orderedlayout.HorizontalLayout.class::isInstance)
                .map(com.vaadin.flow.component.orderedlayout.HorizontalLayout.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(header.getChildren().toList())
                .noneMatch(com.vaadin.flow.component.combobox.ComboBox.class::isInstance);
        // Viewer without admin rights only sees the "Show KPIs" toggle
        assertThat(view.getActionMenuForTest().getSubMenu().getItems()).hasSize(1);
    }

    private static Meter meter(String name, MeterType type) {
        Meter meter = new Meter();
        meter.setName(name);
        meter.setType(type);
        return meter;
    }
}

