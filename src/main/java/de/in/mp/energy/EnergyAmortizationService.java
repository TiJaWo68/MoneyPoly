package de.in.mp.energy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Calculates the spreadsheet-style reading matrix (all meters per date), PV
 * energy balance (self-consumption, self-consumption ratio, autarky degree),
 * financial period benefits, and rolling asset amortizations ("Black Zero" /
 * ROI).
 */
@Service
public class EnergyAmortizationService {

    private static final int CURRENCY_SCALE = 2;
    private static final int QUANTITY_SCALE = 3;
    private static final int PERCENT_SCALE = 1;
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final EnergyAssetRepository assetRepository;
    private final MeterRepository meterRepository;
    private final MeterReadingRepository readingRepository;
    private final EnergyTariffService tariffService;

    public EnergyAmortizationService(
            EnergyAssetRepository assetRepository,
            MeterRepository meterRepository,
            MeterReadingRepository readingRepository,
            EnergyTariffService tariffService) {
        this.assetRepository = assetRepository;
        this.meterRepository = meterRepository;
        this.readingRepository = readingRepository;
        this.tariffService = tariffService;
    }

    /**
     * Builds the chronological list of date rows across all configured meters,
     * computing PV self-consumption, feed-in revenue, self-consumption savings,
     * and EV charging advantage for each date.
     */
    @Transactional(readOnly = true)
    public List<EnergyDateRow> buildDateRows() {
        return buildDateRows(null);
    }

    @Transactional(readOnly = true)
    public List<EnergyDateRow> buildDateRows(UUID houseId) {
        return buildDateRows(houseId, false);
    }

    @Transactional(readOnly = true)
    public List<EnergyDateRow> buildDateRows(UUID houseId, boolean monthEndOnly) {
        List<Meter> meters = houseId == null
                ? meterRepository.findAllByOrderByDisplayOrderAscNameAsc()
                : meterRepository.findByHouseIdOrderByDisplayOrderAscNameAsc(houseId);
        Map<UUID, Meter> metersById = meters.stream()
                .collect(Collectors.toMap(Meter::getId, m -> m));

        List<MeterReading> allReadings = readingRepository.findAllByOrderByReadingDateAsc();
        Map<LocalDate, Map<UUID, MeterReading>> groupedByDate = new TreeMap<>();
        for (MeterReading reading : allReadings) {
            if (!metersById.containsKey(reading.getMeterId())) {
                continue;
            }
            if (monthEndOnly && !isMonthEnd(reading.getReadingDate())) {
                continue;
            }
            groupedByDate
                    .computeIfAbsent(reading.getReadingDate(), d -> new LinkedHashMap<>())
                    .put(reading.getMeterId(), reading);
        }
        if (monthEndOnly) {
            recomputeMonthEndDeltas(groupedByDate, metersById);
        }

        List<EnergyDateRow> rows = new ArrayList<>();
        LocalDate previousDate = null;

        for (Map.Entry<LocalDate, Map<UUID, MeterReading>> entry : groupedByDate.entrySet()) {
            LocalDate currentDate = entry.getKey();
            Map<UUID, MeterReading> readingsAtDate = entry.getValue();

            BigDecimal gridImportDelta = sumDeltaForType(readingsAtDate, metersById, MeterType.GRID_IMPORT);
            BigDecimal gridExportDelta = sumDeltaForType(readingsAtDate, metersById, MeterType.GRID_EXPORT);
            BigDecimal pvGenDelta = sumDeltaForType(readingsAtDate, metersById, MeterType.PV_GENERATION);
            BigDecimal wallboxCompanyDelta = sumDeltaForType(readingsAtDate, metersById, MeterType.WALLBOX_COMPANY);

            BigDecimal selfConsumption = pvGenDelta.subtract(gridExportDelta).max(BigDecimal.ZERO)
                    .setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
            BigDecimal totalHouseholdConsumption = gridImportDelta.add(selfConsumption);

            BigDecimal selfConsumptionRatio = percentage(selfConsumption, pvGenDelta);
            BigDecimal autarkyRatio = percentage(selfConsumption, totalHouseholdConsumption);

            BigDecimal importPrice = zeroIfNull(resolveEffectiveUnitPrice(
                    houseId, MeterType.GRID_IMPORT, previousDate, currentDate));
            BigDecimal exportPrice = zeroIfNull(resolveEffectiveUnitPrice(
                    houseId, MeterType.GRID_EXPORT, previousDate, currentDate));
            BigDecimal wallboxReimbursementRate = zeroIfNull(resolveEffectiveUnitPrice(
                    houseId, MeterType.WALLBOX_COMPANY, previousDate, currentDate));

            BigDecimal feedInRevenue = gridExportDelta.multiply(exportPrice)
                    .setScale(CURRENCY_SCALE, RoundingMode.HALF_UP);
            BigDecimal selfConsumptionSavings = selfConsumption.multiply(importPrice)
                    .setScale(CURRENCY_SCALE, RoundingMode.HALF_UP);
            BigDecimal wallboxAdvantage = calculateWallboxAdvantage(
                    wallboxCompanyDelta, wallboxReimbursementRate, exportPrice);

            BigDecimal totalBenefit = feedInRevenue
                    .add(selfConsumptionSavings)
                    .add(wallboxAdvantage)
                    .setScale(CURRENCY_SCALE, RoundingMode.HALF_UP);

            String combinedNotes = readingsAtDate.values().stream()
                    .map(MeterReading::getNotes)
                    .filter(n -> n != null && !n.isBlank())
                    .distinct()
                    .collect(Collectors.joining("; "));

            rows.add(new EnergyDateRow(
                    currentDate,
                    Map.copyOf(readingsAtDate),
                    gridImportDelta,
                    gridExportDelta,
                    pvGenDelta,
                    selfConsumption,
                    selfConsumptionRatio,
                    autarkyRatio,
                    feedInRevenue,
                    selfConsumptionSavings,
                    wallboxAdvantage,
                    totalBenefit,
                    combinedNotes));

            previousDate = currentDate;
        }

        return rows;
    }

    /**
     * Calculates the average consumption delta per meter across all periods that
     * actually have a measured delta (`consumptionDelta != null`), so intermediate
     * dates without a reading for a given meter never dilute its average.
     */
    public Map<UUID, BigDecimal> calculateAverageDeltasByMeter(List<EnergyDateRow> dateRows) {
        Map<UUID, BigDecimal> sums = new LinkedHashMap<>();
        Map<UUID, Integer> counts = new LinkedHashMap<>();

        if (dateRows != null) {
            for (EnergyDateRow row : dateRows) {
                for (MeterReading reading : row.readingsByMeter().values()) {
                    BigDecimal delta = reading.getConsumptionDelta();
                    if (delta != null) {
                        UUID meterId = reading.getMeterId();
                        sums.merge(meterId, delta, BigDecimal::add);
                        counts.merge(meterId, 1, Integer::sum);
                    }
                }
            }
        }

        Map<UUID, BigDecimal> averages = new LinkedHashMap<>();
        for (Map.Entry<UUID, BigDecimal> entry : sums.entrySet()) {
            UUID meterId = entry.getKey();
            int count = counts.getOrDefault(meterId, 0);
            if (count > 0) {
                averages.put(meterId,
                        entry.getValue().divide(BigDecimal.valueOf(count), QUANTITY_SCALE, RoundingMode.HALF_UP));
            }
        }
        return averages;
    }

    /**
     * Calculates the cumulative amortization summary for every configured
     * {@link EnergyAsset}.
     */
    @Transactional(readOnly = true)
    public List<AssetAmortizationSummary> calculateAssetAmortizations() {
        return calculateAssetAmortizations(null);
    }

    @Transactional(readOnly = true)
    public List<AssetAmortizationSummary> calculateAssetAmortizations(UUID houseId) {
        List<EnergyAsset> assets = houseId == null
                ? assetRepository.findAllByOrderByInstallationDateAscNameAsc()
                : assetRepository.findByHouseIdOrderByInstallationDateAscNameAsc(houseId);
        if (assets.isEmpty()) {
            return List.of();
        }

        List<Meter> meters = houseId == null
                ? meterRepository.findAllByOrderByDisplayOrderAscNameAsc()
                : meterRepository.findByHouseIdOrderByDisplayOrderAscNameAsc(houseId);
        Map<UUID, Meter> metersById = meters.stream()
                .collect(Collectors.toMap(Meter::getId, m -> m));
        List<EnergyDateRow> dateRows = buildDateRows(houseId);

        List<AssetAmortizationSummary> summaries = new ArrayList<>();
        for (EnergyAsset asset : assets) {
            summaries.add(buildSummaryForAsset(asset, meters, metersById, dateRows));
        }
        return summaries;
    }

    @Transactional(readOnly = true)
    public BigDecimal calculateTotalInvestmentCost() {
        return calculateTotalInvestmentCost(null);
    }

    @Transactional(readOnly = true)
    public BigDecimal calculateTotalInvestmentCost(UUID houseId) {
        List<EnergyAsset> assets = houseId == null
                ? assetRepository.findAllByOrderByInstallationDateAscNameAsc()
                : assetRepository.findByHouseIdOrderByInstallationDateAscNameAsc(houseId);
        return assets.stream()
                .map(EnergyAsset::getInvestmentCost)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(CURRENCY_SCALE, RoundingMode.HALF_UP);
    }

    @Transactional(readOnly = true)
    public BigDecimal calculateTotalAmortized() {
        return calculateTotalAmortized(null);
    }

    @Transactional(readOnly = true)
    public BigDecimal calculateTotalAmortized(UUID houseId) {
        return calculateAssetAmortizations(houseId).stream()
                .map(AssetAmortizationSummary::totalAmortizedEur)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(CURRENCY_SCALE, RoundingMode.HALF_UP);
    }

    @Transactional(readOnly = true)
    public BigDecimal calculateTotalRemainingCost() {
        return calculateTotalRemainingCost(null);
    }

    @Transactional(readOnly = true)
    public BigDecimal calculateTotalRemainingCost(UUID houseId) {
        return calculateAssetAmortizations(houseId).stream()
                .map(AssetAmortizationSummary::remainingCostEur)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(CURRENCY_SCALE, RoundingMode.HALF_UP);
    }

    private AssetAmortizationSummary buildSummaryForAsset(
            EnergyAsset asset,
            List<Meter> allMeters,
            Map<UUID, Meter> metersById,
            List<EnergyDateRow> dateRows) {

        List<Meter> assetMeters = allMeters.stream()
                .filter(m -> asset.getId() != null && asset.getId().equals(m.getAssetId()))
                .toList();

        boolean hasPvMeters = assetMeters.stream().anyMatch(m -> m.getType() == MeterType.PV_GENERATION);
        boolean hasExportMeters = assetMeters.stream().anyMatch(m -> m.getType() == MeterType.GRID_EXPORT);
        boolean hasWallboxMeters = assetMeters.stream().anyMatch(m -> m.getType() == MeterType.WALLBOX_COMPANY);

        BigDecimal cumulativeFeedIn = BigDecimal.ZERO;
        BigDecimal cumulativeSavings = BigDecimal.ZERO;
        BigDecimal cumulativeWallbox = BigDecimal.ZERO;

        LocalDate prevDate = null;
        for (EnergyDateRow row : dateRows) {
            LocalDate date = row.readingDate();
            if (asset.getInstallationDate() != null && date.isBefore(asset.getInstallationDate())) {
                prevDate = date;
                continue;
            }

            BigDecimal importPrice = zeroIfNull(
                    tariffService.resolveEffectiveUnitPrice(MeterType.GRID_IMPORT, prevDate, date));
            BigDecimal exportPrice = zeroIfNull(
                    tariffService.resolveEffectiveUnitPrice(MeterType.GRID_EXPORT, prevDate, date));
            BigDecimal wallboxRate = zeroIfNull(
                    tariffService.resolveEffectiveUnitPrice(MeterType.WALLBOX_COMPANY, prevDate, date));

            if (hasPvMeters) {
                BigDecimal assetPvDelta = sumDeltaForAssetAndType(
                        row.readingsByMeter(), metersById, asset.getId(), MeterType.PV_GENERATION);
                BigDecimal assetExportDelta = hasExportMeters
                        ? sumDeltaForAssetAndType(row.readingsByMeter(), metersById, asset.getId(),
                                MeterType.GRID_EXPORT)
                        : BigDecimal.ZERO;

                BigDecimal assetSelfConsumption = assetPvDelta.subtract(assetExportDelta).max(BigDecimal.ZERO);
                cumulativeFeedIn = cumulativeFeedIn.add(
                        assetExportDelta.multiply(exportPrice).setScale(CURRENCY_SCALE, RoundingMode.HALF_UP));
                cumulativeSavings = cumulativeSavings.add(
                        assetSelfConsumption.multiply(importPrice).setScale(CURRENCY_SCALE, RoundingMode.HALF_UP));
            } else if (hasExportMeters) {
                BigDecimal assetExportDelta = sumDeltaForAssetAndType(
                        row.readingsByMeter(), metersById, asset.getId(), MeterType.GRID_EXPORT);
                cumulativeFeedIn = cumulativeFeedIn.add(
                        assetExportDelta.multiply(exportPrice).setScale(CURRENCY_SCALE, RoundingMode.HALF_UP));
            }

            if (hasWallboxMeters) {
                BigDecimal wbDelta = sumDeltaForAssetAndType(
                        row.readingsByMeter(), metersById, asset.getId(), MeterType.WALLBOX_COMPANY);
                cumulativeWallbox = cumulativeWallbox.add(
                        calculateWallboxAdvantage(wbDelta, wallboxRate, exportPrice));
            }

            prevDate = date;
        }

        BigDecimal initial = asset.getInitialAmortized() != null ? asset.getInitialAmortized() : BigDecimal.ZERO;
        BigDecimal totalAmortized = initial
                .add(cumulativeFeedIn)
                .add(cumulativeSavings)
                .add(cumulativeWallbox)
                .setScale(CURRENCY_SCALE, RoundingMode.HALF_UP);

        BigDecimal cost = asset.getInvestmentCost() != null ? asset.getInvestmentCost() : BigDecimal.ZERO;
        BigDecimal remaining = cost.subtract(totalAmortized).max(BigDecimal.ZERO)
                .setScale(CURRENCY_SCALE, RoundingMode.HALF_UP);
        BigDecimal percent = percentage(totalAmortized, cost);
        boolean isAmortized = cost.signum() > 0 && totalAmortized.compareTo(cost) >= 0;

        return new AssetAmortizationSummary(
                asset,
                cumulativeFeedIn.setScale(CURRENCY_SCALE, RoundingMode.HALF_UP),
                cumulativeSavings.setScale(CURRENCY_SCALE, RoundingMode.HALF_UP),
                cumulativeWallbox.setScale(CURRENCY_SCALE, RoundingMode.HALF_UP),
                totalAmortized,
                remaining,
                percent,
                isAmortized);
    }

    private BigDecimal calculateWallboxAdvantage(
            BigDecimal chargedKwh, BigDecimal reimbursementRate, BigDecimal exportPrice) {
        if (chargedKwh == null || chargedKwh.signum() <= 0 || reimbursementRate == null
                || reimbursementRate.signum() <= 0) {
            return BigDecimal.ZERO.setScale(CURRENCY_SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal opportunityCost = exportPrice != null ? exportPrice : BigDecimal.ZERO;
        BigDecimal netMarginPerKwh = reimbursementRate.subtract(opportunityCost).max(BigDecimal.ZERO);
        return chargedKwh.multiply(netMarginPerKwh).setScale(CURRENCY_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal sumDeltaForType(
            Map<UUID, MeterReading> readingsAtDate, Map<UUID, Meter> metersById, MeterType type) {
        BigDecimal sum = BigDecimal.ZERO;
        for (MeterReading reading : readingsAtDate.values()) {
            Meter meter = metersById.get(reading.getMeterId());
            if (meter != null && meter.getType() == type && reading.getConsumptionDelta() != null) {
                // Only include main import meter or asset-linked meters for household balance
                // if multiple exist
                if (type == MeterType.GRID_IMPORT && isSubMeter(meter)) {
                    continue;
                }
                sum = sum.add(reading.getConsumptionDelta());
            }
        }
        return sum.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
    }

    private boolean isSubMeter(Meter meter) {
        return meter.getOverflowThreshold() != null;
    }

    private BigDecimal sumDeltaForAssetAndType(
            Map<UUID, MeterReading> readingsAtDate,
            Map<UUID, Meter> metersById,
            UUID assetId,
            MeterType type) {
        BigDecimal sum = BigDecimal.ZERO;
        for (MeterReading reading : readingsAtDate.values()) {
            Meter meter = metersById.get(reading.getMeterId());
            if (meter != null
                    && assetId.equals(meter.getAssetId())
                    && meter.getType() == type
                    && reading.getConsumptionDelta() != null) {
                sum = sum.add(reading.getConsumptionDelta());
            }
        }
        return sum.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
    }

    private static boolean isMonthEnd(LocalDate date) {
        return date != null && date.getDayOfMonth() == date.lengthOfMonth();
    }

    private void recomputeMonthEndDeltas(
            Map<LocalDate, Map<UUID, MeterReading>> groupedByDate,
            Map<UUID, Meter> metersById) {
        Map<UUID, BigDecimal> prevValueByMeter = new LinkedHashMap<>();
        for (Map<UUID, MeterReading> readingsAtDate : groupedByDate.values()) {
            for (Map.Entry<UUID, MeterReading> entry : readingsAtDate.entrySet()) {
                MeterReading src = entry.getValue();
                MeterReading copy = new MeterReading();
                copy.setId(src.getId());
                copy.setMeterId(src.getMeterId());
                copy.setReadingDate(src.getReadingDate());
                copy.setReadingValue(src.getReadingValue());
                copy.setNotes(src.getNotes());

                BigDecimal prevVal = prevValueByMeter.get(src.getMeterId());
                if (prevVal != null && src.getReadingValue() != null
                        && src.getReadingValue().compareTo(prevVal) >= 0) {
                    Meter meter = metersById.get(src.getMeterId());
                    BigDecimal factor = meter != null && meter.getConversionFactor() != null
                            ? meter.getConversionFactor()
                            : BigDecimal.ONE;
                    copy.setConsumptionDelta(src.getReadingValue().subtract(prevVal).multiply(factor)
                            .setScale(QUANTITY_SCALE, RoundingMode.HALF_UP));
                } else {
                    copy.setConsumptionDelta(src.getConsumptionDelta());
                }
                prevValueByMeter.put(src.getMeterId(), src.getReadingValue());
                entry.setValue(copy);
            }
        }
    }

    private BigDecimal percentage(BigDecimal part, BigDecimal total) {
        if (total == null || total.signum() <= 0 || part == null || part.signum() <= 0) {
            return BigDecimal.ZERO.setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
        }
        return part.multiply(ONE_HUNDRED).divide(total, PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal resolveEffectiveUnitPrice(
            UUID houseId, MeterType type, LocalDate periodStart, LocalDate periodEnd) {
        if (houseId == null) {
            return tariffService.resolveEffectiveUnitPrice(type, periodStart, periodEnd);
        }
        return tariffService.resolveEffectiveUnitPrice(houseId, type, periodStart, periodEnd);
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}