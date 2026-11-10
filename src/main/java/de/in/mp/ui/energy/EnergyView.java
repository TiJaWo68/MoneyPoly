package de.in.mp.ui.energy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.grid.HeaderRow;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.theme.lumo.LumoUtility;

import de.in.mp.energy.AssetAmortizationSummary;
import de.in.mp.energy.EnergyAmortizationService;
import de.in.mp.energy.EnergyAsset;
import de.in.mp.energy.EnergyAssetService;
import de.in.mp.energy.EnergyDateRow;
import de.in.mp.energy.EnergyTariff;
import de.in.mp.energy.EnergyTariffService;
import de.in.mp.energy.Meter;
import de.in.mp.energy.MeterReading;
import de.in.mp.energy.MeterReadingService;
import de.in.mp.energy.MeterService;
import de.in.mp.energy.MeterType;
import de.in.mp.i18n.MoneyFormatter;
import de.in.mp.ui.GridActionButton;
import de.in.mp.ui.layout.MainLayout;
import jakarta.annotation.security.PermitAll;

/**
 * Main view for the Energy & Meters module ({@code /energy}):
 * <ul>
 *   <li>Spreadsheet-like overview of all meter readings by date with one-click
 *       batch row entry ("Neue Zeile / Ablesung erfassen").</li>
 *   <li>Dynamic ROI & amortization cockpit for PV systems and wallbox
 *       investments, automatically recalculated whenever readings change.</li>
 *   <li>Master data configuration for meters and time-bounded tariffs.</li>
 * </ul>
 */
@Route(value = "energy", layout = MainLayout.class)
@PermitAll
public class EnergyView extends VerticalLayout implements HasDynamicTitle {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final EnergyAssetService assetService;
    private final MeterService meterService;
    private final MeterReadingService readingService;
    private final EnergyTariffService tariffService;
    private final EnergyAmortizationService amortizationService;

    private final BatchMeterReadingDialog batchReadingDialog;
    private final EnergyAssetEditorDialog assetEditorDialog;
    private final MeterEditorDialog meterEditorDialog;
    private final EnergyTariffEditorDialog tariffEditorDialog;

    private final Span totalInvestmentValue = new Span();
    private final Span totalAmortizedValue = new Span();
    private final Span remainingCostValue = new Span();
    private final Span activeMetersValue = new Span();

    private final Grid<EnergyDateRow> readingsGrid = new Grid<>(EnergyDateRow.class, false);
    private final Grid<AssetAmortizationSummary> amortizationGrid = new Grid<>(AssetAmortizationSummary.class, false);
    private final Grid<Meter> metersGrid = new Grid<>(Meter.class, false);
    private final Grid<EnergyTariff> tariffsGrid = new Grid<>(EnergyTariff.class, false);

    private final Tabs tabBar = new Tabs();
    private final Tab readingsTab;
    private final Tab amortizationTab;
    private final Tab masterDataTab;
    private final VerticalLayout tabContent = new VerticalLayout();

    private Map<UUID, String> assetNamesById = Map.of();
    private Map<UUID, BigDecimal> averageDeltasByMeter = Map.of();
    private HeaderRow groupHeaderRow;

    public EnergyView(
            EnergyAssetService assetService,
            MeterService meterService,
            MeterReadingService readingService,
            EnergyTariffService tariffService,
            EnergyAmortizationService amortizationService) {
        this.assetService = assetService;
        this.meterService = meterService;
        this.readingService = readingService;
        this.tariffService = tariffService;
        this.amortizationService = amortizationService;

        this.batchReadingDialog = new BatchMeterReadingDialog(meterService, readingService);
        this.assetEditorDialog = new EnergyAssetEditorDialog(assetService);
        this.meterEditorDialog = new MeterEditorDialog(meterService, assetService);
        this.tariffEditorDialog = new EnergyTariffEditorDialog(tariffService);

        this.readingsTab = new Tab(getTranslation("energy.tab.readings"));
        this.amortizationTab = new Tab(getTranslation("energy.tab.amortization"));
        this.masterDataTab = new Tab(getTranslation("energy.tab.masterdata"));

        setSizeFull();
        setPadding(true);
        setSpacing(false);

        configureAmortizationGrid();
        configureMetersGrid();
        configureTariffsGrid();

        tabBar.add(readingsTab, amortizationTab, masterDataTab);
        tabBar.setWidthFull();
        tabBar.addSelectedChangeListener(event -> renderSelectedTab());

        tabContent.setSizeFull();
        tabContent.setPadding(false);

        add(createHeader(), createKpiRow(), tabBar, tabContent);
        refreshAll();
        renderSelectedTab();
    }

    @Override
    public String getPageTitle() {
        return getTranslation("energy.view.page-title");
    }

    private HorizontalLayout createHeader() {
        H2 title = new H2(getTranslation("energy.view.title"));
        title.addClassName(LumoUtility.Margin.NONE);

        Button batchEntryButton = new Button(
                getTranslation("energy.button.batch-entry"),
                VaadinIcon.PLUS.create());
        batchEntryButton.setTooltipText(getTranslation("energy.button.batch-entry"));
        batchEntryButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        batchEntryButton.addClickListener(event -> batchReadingDialog.openForCreate(this::refreshAll));

        HorizontalLayout header = new HorizontalLayout(title, batchEntryButton);
        header.setWidthFull();
        header.setDefaultVerticalComponentAlignment(Alignment.CENTER);
        header.expand(title);
        header.addClassName(LumoUtility.Margin.Bottom.MEDIUM);
        return header;
    }

    private HorizontalLayout createKpiRow() {
        VerticalLayout investmentCard = createKpiCard(
                getTranslation("energy.kpi.total-investment"), totalInvestmentValue);
        VerticalLayout amortizedCard = createKpiCard(
                getTranslation("energy.kpi.total-amortized"), totalAmortizedValue);
        VerticalLayout remainingCard = createKpiCard(
                getTranslation("energy.kpi.remaining-cost"), remainingCostValue);
        VerticalLayout metersCard = createKpiCard(
                getTranslation("energy.kpi.active-meters"), activeMetersValue);

        HorizontalLayout row = new HorizontalLayout(investmentCard, amortizedCard, remainingCard, metersCard);
        row.setWidthFull();
        row.setSpacing(true);
        row.addClassName(LumoUtility.Margin.Bottom.MEDIUM);
        return row;
    }

    private VerticalLayout createKpiCard(String label, Span value) {
        value.addClassNames(LumoUtility.FontSize.XXLARGE, LumoUtility.FontWeight.BOLD);

        Span labelSpan = new Span(label);
        labelSpan.addClassNames(LumoUtility.TextColor.SECONDARY, LumoUtility.FontSize.SMALL);

        VerticalLayout card = new VerticalLayout(labelSpan, value);
        card.setPadding(true);
        card.setSpacing(false);
        card.setMinWidth("200px");
        card.addClassNames(
                LumoUtility.Background.CONTRAST_5,
                LumoUtility.BorderRadius.LARGE,
                LumoUtility.BoxShadow.SMALL);
        return card;
    }

    private void renderSelectedTab() {
        tabContent.removeAll();
        Tab selected = tabBar.getSelectedTab();
        if (selected == readingsTab) {
            readingsGrid.setSizeFull();
            tabContent.add(readingsGrid);
        } else if (selected == amortizationTab) {
            Button newAssetButton = new Button(
                    getTranslation("energy.button.new-asset"),
                    VaadinIcon.PLUS.create(),
                    e -> assetEditorDialog.openForCreate(this::refreshAll));
            newAssetButton.setTooltipText(getTranslation("energy.button.new-asset"));
            newAssetButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

            HorizontalLayout toolbar = new HorizontalLayout(newAssetButton);
            toolbar.setWidthFull();
            amortizationGrid.setSizeFull();
            tabContent.add(toolbar, amortizationGrid);
        } else {
            tabContent.add(createMasterDataLayout());
        }
    }

    private Component createMasterDataLayout() {
        H3 metersTitle = new H3(getTranslation("energy.meter.column.name"));
        metersTitle.addClassName(LumoUtility.Margin.NONE);
        Button newMeterButton = new Button(
                getTranslation("energy.button.new-meter"),
                VaadinIcon.PLUS.create(),
                e -> meterEditorDialog.openForCreate(this::refreshAll));
        newMeterButton.setTooltipText(getTranslation("energy.button.new-meter"));
        newMeterButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
        HorizontalLayout metersHeader = new HorizontalLayout(metersTitle, newMeterButton);
        metersHeader.setWidthFull();
        metersHeader.setDefaultVerticalComponentAlignment(Alignment.CENTER);
        metersHeader.expand(metersTitle);

        H3 tariffsTitle = new H3(getTranslation("energy.tariff.column.name"));
        tariffsTitle.addClassName(LumoUtility.Margin.NONE);
        Button newTariffButton = new Button(
                getTranslation("energy.button.new-tariff"),
                VaadinIcon.PLUS.create(),
                e -> tariffEditorDialog.openForCreate(this::refreshAll));
        newTariffButton.setTooltipText(getTranslation("energy.button.new-tariff"));
        newTariffButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
        HorizontalLayout tariffsHeader = new HorizontalLayout(tariffsTitle, newTariffButton);
        tariffsHeader.setWidthFull();
        tariffsHeader.setDefaultVerticalComponentAlignment(Alignment.CENTER);
        tariffsHeader.expand(tariffsTitle);

        metersGrid.setHeight("300px");
        tariffsGrid.setHeight("260px");

        VerticalLayout layout = new VerticalLayout(metersHeader, metersGrid, tariffsHeader, tariffsGrid);
        layout.setSizeFull();
        layout.setPadding(false);
        return layout;
    }

    private void rebuildReadingsGridColumns(List<Meter> activeMeters) {
        if (groupHeaderRow != null) {
            readingsGrid.removeHeaderRow(groupHeaderRow);
            groupHeaderRow = null;
        }
        readingsGrid.removeAllColumns();

        List<Meter> orderedMeters = new ArrayList<>(activeMeters);
        orderedMeters.sort((a, b) -> {
            int gCmp = Integer.compare(groupRank(a.getType()), groupRank(b.getType()));
            if (gCmp != 0) {
                return gCmp;
            }
            int oCmp = Integer.compare(a.getDisplayOrder(), b.getDisplayOrder());
            return oCmp != 0 ? oCmp : a.getName().compareToIgnoreCase(b.getName());
        });

        readingsGrid.addColumn(row -> DATE_FORMAT.format(row.readingDate()))
                .setHeader(getTranslation("energy.readings.column.date"))
                .setFrozen(true)
                .setAutoWidth(true);

        List<MeterColumnEntry> meterColumns = new ArrayList<>();
        for (Meter meter : orderedMeters) {
            UUID meterId = meter.getId();
            Grid.Column<EnergyDateRow> col = readingsGrid
                    .addColumn(row -> formatMeterCell(row.readingFor(meterId)))
                    .setHeader(shortColumnHeader(meter))
                    .setTooltipGenerator(row -> buildMeterTooltip(meter, row.readingFor(meterId)))
                    .setTextAlign(ColumnTextAlign.END)
                    .setAutoWidth(true);
            meterColumns.add(new MeterColumnEntry(meter, groupRank(meter.getType()), col));
        }

        Grid.Column<EnergyDateRow> selfConsumptionCol = readingsGrid
                .addColumn(row -> formatQuantity(row.pvSelfConsumptionKwh()))
                .setHeader(getTranslation("energy.readings.column.self-consumption"))
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);

        Grid.Column<EnergyDateRow> feedInCol = readingsGrid
                .addColumn(row -> formatCurrency(row.feedInRevenueEur()))
                .setHeader(getTranslation("energy.readings.column.feed-in-revenue"))
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);

        Grid.Column<EnergyDateRow> savingsCol = readingsGrid
                .addColumn(row -> formatCurrency(row.selfConsumptionSavingsEur()))
                .setHeader(getTranslation("energy.readings.column.self-consumption-savings"))
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);

        Grid.Column<EnergyDateRow> wallboxCol = readingsGrid
                .addColumn(row -> formatCurrency(row.wallboxAdvantageEur()))
                .setHeader(getTranslation("energy.readings.column.wallbox-advantage"))
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);

        Grid.Column<EnergyDateRow> benefitCol = readingsGrid
                .addColumn(row -> formatCurrency(row.totalPeriodBenefitEur()))
                .setHeader(getTranslation("energy.readings.column.total-benefit"))
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);

        readingsGrid.addColumn(EnergyDateRow::notes)
                .setHeader(getTranslation("energy.readings.column.notes"))
                .setAutoWidth(true);

        readingsGrid.addColumn(new ComponentRenderer<>(this::renderReadingRowActions))
                .setHeader(getTranslation("energy.readings.column.actions"))
                .setAutoWidth(true);

        groupHeaderRow = readingsGrid.prependHeaderRow();
        applyGroupedHeaders(groupHeaderRow, meterColumns,
                List.of(selfConsumptionCol, feedInCol, savingsCol, wallboxCol, benefitCol));

        readingsGrid.addThemeVariants(
                GridVariant.LUMO_ROW_STRIPES,
                GridVariant.LUMO_COLUMN_BORDERS,
                GridVariant.LUMO_COMPACT);
    }

    private void applyGroupedHeaders(
            HeaderRow topRow,
            List<MeterColumnEntry> meterColumns,
            List<Grid.Column<EnergyDateRow>> evalColumns) {
        int i = 0;
        while (i < meterColumns.size()) {
            int currentGroup = meterColumns.get(i).groupRank();
            String unit = meterColumns.get(i).meter().getUnit();
            List<Grid.Column<EnergyDateRow>> run = new ArrayList<>();
            while (i < meterColumns.size() && meterColumns.get(i).groupRank() == currentGroup) {
                run.add(meterColumns.get(i).column());
                i++;
            }
            String groupTitle = groupHeaderLabel(currentGroup, unit);
            if (run.size() == 1) {
                topRow.getCell(run.get(0)).setText(groupTitle);
            } else {
                topRow.join(run.toArray(new Grid.Column[0])).setText(groupTitle);
            }
        }

        if (evalColumns.size() > 1) {
            topRow.join(evalColumns.toArray(new Grid.Column[0]))
                    .setText(getTranslation("energy.readings.group.evaluation"));
        }
    }

    private String groupHeaderLabel(int groupRank, String unit) {
        return switch (groupRank) {
            case 1 -> getTranslation("energy.readings.group.water", unit);
            case 2 -> getTranslation("energy.readings.group.gas", unit);
            case 3 -> getTranslation("energy.readings.group.electricity", unit);
            case 4 -> getTranslation("energy.readings.group.pv", unit);
            case 5 -> getTranslation("energy.readings.group.wallbox", unit);
            default -> unit;
        };
    }

    static int groupRank(MeterType type) {
        if (type == null) {
            return 99;
        }
        return switch (type) {
            case WATER -> 1;
            case GAS -> 2;
            case GRID_IMPORT, GRID_EXPORT -> 3;
            case PV_GENERATION -> 4;
            case WALLBOX_COMPANY, WALLBOX_PRIVATE -> 5;
        };
    }

    static String shortColumnHeader(Meter meter) {
        if (meter == null || meter.getName() == null) {
            return "";
        }
        String name = meter.getName().trim();
        for (String prefix : List.of("Wasser ", "Strom ", "PV ", "Wallbox ")) {
            if (name.regionMatches(true, 0, prefix, 0, prefix.length())
                    && name.length() > prefix.length()) {
                return name.substring(prefix.length()).trim();
            }
        }
        return name;
    }

    private String formatMeterCell(MeterReading reading) {
        Locale locale = UI.getCurrent() != null ? UI.getCurrent().getLocale() : Locale.GERMAN;
        return formatMeterValueOnly(reading, locale);
    }

    static String formatMeterValueOnly(MeterReading reading, Locale locale) {
        if (reading == null || reading.getReadingValue() == null) {
            return "—";
        }
        return formatQuantityWithLocale(reading.getReadingValue(), locale);
    }

    private String buildMeterTooltip(Meter meter, MeterReading reading) {
        if (reading == null || reading.getReadingValue() == null) {
            return "";
        }
        BigDecimal delta = reading.getConsumptionDelta();
        if (delta == null) {
            return getTranslation("energy.readings.tooltip.initial");
        }
        Locale locale = UI.getCurrent() != null ? UI.getCurrent().getLocale() : Locale.GERMAN;
        BigDecimal avg = averageDeltasByMeter.get(meter.getId());
        String deltaStr = formatQuantityWithLocale(delta, locale);
        String avgStr = avg != null ? formatQuantityWithLocale(avg, locale) : deltaStr;
        String comparison = formatAverageComparison(delta, avg, locale);
        return getTranslation("energy.readings.tooltip.delta",
                deltaStr, meter.getUnit(), avgStr, comparison);
    }

    static String formatAverageComparison(BigDecimal delta, BigDecimal average, Locale locale) {
        if (delta == null || average == null || average.signum() <= 0) {
            return "±0% vs. Ø";
        }
        BigDecimal diffPercent = delta.subtract(average)
                .multiply(new BigDecimal("100"))
                .divide(average, 0, RoundingMode.HALF_UP);
        String sign = diffPercent.signum() > 0 ? "+" : "";
        return sign + diffPercent.toPlainString() + "% vs. Ø";
    }

    private record MeterColumnEntry(
            Meter meter,
            int groupRank,
            Grid.Column<EnergyDateRow> column) {
    }

    private HorizontalLayout renderReadingRowActions(EnergyDateRow row) {
        Button editButton = GridActionButton.create(
                VaadinIcon.EDIT,
                getTranslation("energy.readings.action.edit-row"),
                e -> batchReadingDialog.openForEdit(row, this::refreshAll));

        Button deleteButton = GridActionButton.createDanger(
                VaadinIcon.TRASH,
                getTranslation("energy.readings.action.delete-row"),
                e -> {
                    readingService.deleteReadingsForDate(row.readingDate());
                    Notification.show(getTranslation("energy.notification.deleted"),
                            3000, Notification.Position.BOTTOM_START);
                    refreshAll();
                });

        return new HorizontalLayout(editButton, deleteButton);
    }

    private void configureAmortizationGrid() {
        amortizationGrid.addColumn(s -> s.asset().getName())
                .setHeader(getTranslation("energy.amortization.column.asset"))
                .setAutoWidth(true);

        amortizationGrid.addColumn(s -> DATE_FORMAT.format(s.asset().getInstallationDate()))
                .setHeader(getTranslation("energy.amortization.column.installation-date"))
                .setAutoWidth(true);

        amortizationGrid.addColumn(s -> formatCurrency(s.asset().getInvestmentCost()))
                .setHeader(getTranslation("energy.amortization.column.investment"))
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);

        amortizationGrid.addColumn(s -> formatCurrency(s.asset().getInitialAmortized()))
                .setHeader(getTranslation("energy.amortization.column.initial-amortized"))
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);

        amortizationGrid.addColumn(s -> formatCurrency(s.feedInRevenueEur()))
                .setHeader(getTranslation("energy.amortization.column.feed-in"))
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);

        amortizationGrid.addColumn(s -> formatCurrency(s.selfConsumptionSavingsEur()))
                .setHeader(getTranslation("energy.amortization.column.savings"))
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);

        amortizationGrid.addColumn(s -> formatCurrency(s.wallboxAdvantageEur()))
                .setHeader(getTranslation("energy.amortization.column.wallbox"))
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);

        amortizationGrid.addColumn(new ComponentRenderer<>(this::renderAmortizationProgress))
                .setHeader(getTranslation("energy.amortization.column.progress"))
                .setAutoWidth(true);

        amortizationGrid.addColumn(s -> formatCurrency(s.remainingCostEur()))
                .setHeader(getTranslation("energy.amortization.column.remaining"))
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);

        amortizationGrid.addColumn(new ComponentRenderer<>(this::renderAmortizationStatus))
                .setHeader(getTranslation("energy.amortization.column.status"))
                .setAutoWidth(true);

        amortizationGrid.addColumn(new ComponentRenderer<>(this::renderAssetActions))
                .setHeader(getTranslation("energy.amortization.column.actions"))
                .setAutoWidth(true);

        amortizationGrid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_COLUMN_BORDERS);
    }

    private Component renderAmortizationProgress(AssetAmortizationSummary summary) {
        BigDecimal cost = summary.asset().getInvestmentCost();
        double progress = 0;
        if (cost != null && cost.signum() > 0) {
            progress = summary.totalAmortizedEur().divide(cost, 4, RoundingMode.HALF_UP).doubleValue();
        }
        ProgressBar bar = new ProgressBar(0, 1, Math.max(0, Math.min(1, progress)));
        Span label = new Span(formatCurrency(summary.totalAmortizedEur())
                + " (" + summary.amortizedPercent() + "%)");

        VerticalLayout layout = new VerticalLayout(label, bar);
        layout.setPadding(false);
        layout.setSpacing(false);
        return layout;
    }

    private Span renderAmortizationStatus(AssetAmortizationSummary summary) {
        String key = summary.amortized()
                ? "energy.amortization.status.amortized"
                : "energy.amortization.status.running";
        Span badge = new Span(getTranslation(key));
        badge.getElement().getThemeList().add("badge pill");
        badge.getElement().getThemeList().add(summary.amortized() ? "success" : "primary");
        return badge;
    }

    private HorizontalLayout renderAssetActions(AssetAmortizationSummary summary) {
        Button editButton = GridActionButton.create(
                VaadinIcon.EDIT,
                getTranslation("energy.action.edit"),
                e -> assetEditorDialog.openForEdit(summary.asset(), this::refreshAll));
        return new HorizontalLayout(editButton);
    }

    private void configureMetersGrid() {
        metersGrid.addColumn(Meter::getName)
                .setHeader(getTranslation("energy.meter.column.name"))
                .setAutoWidth(true);

        metersGrid.addColumn(Meter::getSerialNumber)
                .setHeader(getTranslation("energy.meter.column.serial"))
                .setAutoWidth(true);

        metersGrid.addColumn(m -> getTranslation("energy.meter.type." + m.getType().name()))
                .setHeader(getTranslation("energy.meter.column.type"))
                .setAutoWidth(true);

        metersGrid.addColumn(Meter::getUnit)
                .setHeader(getTranslation("energy.meter.column.unit"))
                .setAutoWidth(true);

        metersGrid.addColumn(m -> formatQuantity(m.getConversionFactor()))
                .setHeader(getTranslation("energy.meter.column.conversion"))
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);

        metersGrid.addColumn(m -> m.getOverflowThreshold() != null ? formatQuantity(m.getOverflowThreshold()) : "—")
                .setHeader(getTranslation("energy.meter.column.overflow"))
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);

        metersGrid.addColumn(m -> m.getAssetId() != null ? assetNamesById.getOrDefault(m.getAssetId(), "—") : "—")
                .setHeader(getTranslation("energy.meter.column.asset"))
                .setAutoWidth(true);

        metersGrid.addColumn(Meter::getDisplayOrder)
                .setHeader(getTranslation("energy.meter.column.order"))
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);

        metersGrid.addColumn(m -> getTranslation(m.isActive()
                        ? "energy.meter.status.active"
                        : "energy.meter.status.inactive"))
                .setHeader(getTranslation("energy.meter.column.status"))
                .setAutoWidth(true);

        metersGrid.addColumn(new ComponentRenderer<>(this::renderMeterActions))
                .setHeader(getTranslation("energy.readings.column.actions"))
                .setAutoWidth(true);

        metersGrid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_COLUMN_BORDERS);
    }

    private HorizontalLayout renderMeterActions(Meter meter) {
        Button editButton = GridActionButton.create(
                VaadinIcon.EDIT,
                getTranslation("energy.action.edit"),
                e -> meterEditorDialog.openForEdit(meter, this::refreshAll));
        return new HorizontalLayout(editButton);
    }

    private void configureTariffsGrid() {
        tariffsGrid.addColumn(EnergyTariff::getName)
                .setHeader(getTranslation("energy.tariff.column.name"))
                .setAutoWidth(true);

        tariffsGrid.addColumn(t -> getTranslation("energy.meter.type." + t.getForMeterType().name()))
                .setHeader(getTranslation("energy.tariff.column.type"))
                .setAutoWidth(true);

        tariffsGrid.addColumn(t -> DATE_FORMAT.format(t.getValidFrom()))
                .setHeader(getTranslation("energy.tariff.column.valid-from"))
                .setAutoWidth(true);

        tariffsGrid.addColumn(t -> t.getValidTo() != null
                        ? DATE_FORMAT.format(t.getValidTo())
                        : getTranslation("energy.tariff.valid-open"))
                .setHeader(getTranslation("energy.tariff.column.valid-to"))
                .setAutoWidth(true);

        tariffsGrid.addColumn(t -> t.getUnitPriceEur().stripTrailingZeros().toPlainString() + " €")
                .setHeader(getTranslation("energy.tariff.column.unit-price"))
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);

        tariffsGrid.addColumn(t -> formatCurrency(t.getMonthlyBaseFeeEur()))
                .setHeader(getTranslation("energy.tariff.column.base-fee"))
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);

        tariffsGrid.addColumn(new ComponentRenderer<>(this::renderTariffActions))
                .setHeader(getTranslation("energy.readings.column.actions"))
                .setAutoWidth(true);

        tariffsGrid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_COLUMN_BORDERS);
    }

    private HorizontalLayout renderTariffActions(EnergyTariff tariff) {
        Button editButton = GridActionButton.create(
                VaadinIcon.EDIT,
                getTranslation("energy.action.edit"),
                e -> tariffEditorDialog.openForEdit(tariff, this::refreshAll));

        Button deleteButton = GridActionButton.createDanger(
                VaadinIcon.TRASH,
                getTranslation("energy.action.delete"),
                e -> {
                    tariffService.delete(tariff.getId());
                    Notification.show(getTranslation("energy.notification.deleted"),
                            3000, Notification.Position.BOTTOM_START);
                    refreshAll();
                });

        return new HorizontalLayout(editButton, deleteButton);
    }

    private void refreshAll() {
        List<EnergyAsset> assets = assetService.findAll();
        assetNamesById = assets.stream()
                .filter(a -> a.getId() != null)
                .collect(Collectors.toMap(EnergyAsset::getId, EnergyAsset::getName));

        List<Meter> activeMeters = meterService.findActive();
        List<Meter> allMeters = meterService.findAll();
        List<EnergyTariff> tariffs = tariffService.findAll();
        List<EnergyDateRow> dateRows = amortizationService.buildDateRows();
        averageDeltasByMeter = amortizationService.calculateAverageDeltasByMeter(dateRows);

        rebuildReadingsGridColumns(activeMeters);
        readingsGrid.setItems(dateRows);
        amortizationGrid.setItems(amortizationService.calculateAssetAmortizations());
        metersGrid.setItems(allMeters);
        tariffsGrid.setItems(tariffs);

        totalInvestmentValue.setText(formatCurrency(amortizationService.calculateTotalInvestmentCost()));
        totalAmortizedValue.setText(formatCurrency(amortizationService.calculateTotalAmortized()));
        remainingCostValue.setText(formatCurrency(amortizationService.calculateTotalRemainingCost()));
        activeMetersValue.setText(String.valueOf(activeMeters.size()));
    }

    private String formatCurrency(BigDecimal amount) {
        Locale locale = UI.getCurrent() != null ? UI.getCurrent().getLocale() : Locale.GERMAN;
        return MoneyFormatter.format(amount != null ? amount : BigDecimal.ZERO, locale);
    }

    private String formatQuantity(BigDecimal value) {
        Locale locale = UI.getCurrent() != null ? UI.getCurrent().getLocale() : Locale.GERMAN;
        return formatQuantityWithLocale(value, locale);
    }

    private static String formatQuantityWithLocale(BigDecimal value, Locale locale) {
        if (value == null) {
            return "0";
        }
        NumberFormat numberFormat = NumberFormat.getNumberInstance(locale != null ? locale : Locale.GERMAN);
        numberFormat.setMinimumFractionDigits(0);
        numberFormat.setMaximumFractionDigits(3);
        return numberFormat.format(value);
    }
}

