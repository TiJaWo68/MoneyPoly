package de.in.mp.energy;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Parses and imports historical energy data from an OpenDocument Spreadsheet
 * ({@code .ods}, specifically {@code Zählerstände Abrechnungen TBS10.ods}) into
 * the database.
 */
@Service
public class EnergyOdsImportService {

    private static final String TABLE_NS = "urn:oasis:names:tc:opendocument:xmlns:table:1.0";
    private static final String OFFICE_NS = "urn:oasis:names:tc:opendocument:xmlns:office:1.0";
    private static final DateTimeFormatter GERMAN_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public static final String ASSET_PV_DACH = "PV Dach";
    public static final String ASSET_PV_GARTENHAUS = "PV Gartenhaus";
    public static final String ASSET_WALLBOX = "Wallbox";

    private static final Map<Integer, ColumnSpec> ODS_METER_COLUMNS = Map.ofEntries(
            Map.entry(1, new ColumnSpec("Wasser Gesamt", MeterType.WATER, "m3", 10, null, null, false)),
            Map.entry(2, new ColumnSpec("Wasser HV", MeterType.WATER, "m3", 20, null, null, false)),
            Map.entry(3, new ColumnSpec("Wasser HH", MeterType.WATER, "m3", 30, null, null, false)),
            Map.entry(4, new ColumnSpec("Gas", MeterType.GAS, "m3", 40, null, null, false)),
            Map.entry(5, new ColumnSpec("Strom Gesamt", MeterType.GRID_IMPORT, "kWh", 50, null, null, false)),
            Map.entry(6, new ColumnSpec("Strom Einspeisung", MeterType.GRID_EXPORT, "kWh", 60, null, ASSET_PV_DACH, false)),
            Map.entry(7, new ColumnSpec("Strom HV", MeterType.GRID_IMPORT, "kWh", 70, new BigDecimal("100000"), null, false)),
            Map.entry(8, new ColumnSpec("Strom HH", MeterType.GRID_IMPORT, "kWh", 80, new BigDecimal("100000"), null, false)),
            Map.entry(9, new ColumnSpec("Wallbox Laden", MeterType.WALLBOX_PRIVATE, "kWh", 110, null, ASSET_WALLBOX, false)),
            Map.entry(11, new ColumnSpec("PV Gartenhaus", MeterType.PV_GENERATION, "kWh", 90, null, ASSET_PV_GARTENHAUS, true)),
            Map.entry(14, new ColumnSpec("PV Dach", MeterType.PV_GENERATION, "kWh", 100, null, ASSET_PV_DACH, true)),
            Map.entry(23, new ColumnSpec("Wallbox weiß", MeterType.WALLBOX_COMPANY, "kWh", 120, null, ASSET_WALLBOX, true)),
            Map.entry(24, new ColumnSpec("Wallbox rot", MeterType.WALLBOX_PRIVATE, "kWh", 130, null, ASSET_WALLBOX, true)),
            Map.entry(25, new ColumnSpec("Wallbox ID4", MeterType.WALLBOX_COMPANY, "kWh", 140, null, ASSET_WALLBOX, true)));

    private final EnergyAssetService assetService;
    private final MeterService meterService;
    private final EnergyTariffService tariffService;
    private final MeterReadingService readingService;

    public EnergyOdsImportService(
            EnergyAssetService assetService,
            MeterService meterService,
            EnergyTariffService tariffService,
            MeterReadingService readingService) {
        this.assetService = assetService;
        this.meterService = meterService;
        this.tariffService = tariffService;
        this.readingService = readingService;
    }

    /**
     * Imports assets, meters, tariffs, and all reading rows from the provided
     * {@code .ods} stream idempotently.
     */
    @Transactional
    public OdsImportResult importOds(InputStream odsStream, OdsAssetCostDefaults costDefaults) {
        ParsedOdsData parsed = parseOds(odsStream);

        Map<String, EnergyAsset> assetsByName = ensureAssets(costDefaults);
        Map<String, Meter> metersByName = ensureMeters(assetsByName);
        int tariffsCreated = ensureTariffs(parsed);

        Map<String, BigDecimal> runningCumulativeForDeltas = new LinkedHashMap<>();
        BigDecimal previousGartenhausValue = null;

        int datesProcessed = 0;
        int readingsSaved = 0;

        for (ParsedRow row : parsed.rows()) {
            Map<UUID, BigDecimal> batchValues = new LinkedHashMap<>();

            for (ColumnSpec spec : ODS_METER_COLUMNS.values()) {
                BigDecimal rawValue = row.valuesByColumn().get(spec.meterName());
                if (rawValue == null) {
                    continue;
                }
                Meter meter = metersByName.get(spec.meterName());
                if (meter == null) {
                    continue;
                }

                BigDecimal valueToRecord;
                if ("PV Gartenhaus".equals(spec.meterName())) {
                    // In TBS10.ods, Gartenhaus is cumulative until 31.05.2026 (4100) and switches to monthly deltas from 01.07.2026 (1.3, 44, ...)
                    if (previousGartenhausValue != null && rawValue.compareTo(previousGartenhausValue) < 0) {
                        BigDecimal accumulated = runningCumulativeForDeltas
                                .getOrDefault(spec.meterName(), previousGartenhausValue)
                                .add(rawValue);
                        runningCumulativeForDeltas.put(spec.meterName(), accumulated);
                        valueToRecord = accumulated;
                    } else {
                        previousGartenhausValue = rawValue;
                        runningCumulativeForDeltas.put(spec.meterName(), rawValue);
                        valueToRecord = rawValue;
                    }
                } else if (spec.deltaSeries()) {
                    // Convert monthly delta columns (PV Dach, Wallbox weiß/rot/ID4) into cumulative meter readings
                    BigDecimal currentCumulative = runningCumulativeForDeltas.get(spec.meterName());
                    if (currentCumulative == null) {
                        // First entry is baseline
                        currentCumulative = rawValue.setScale(3, RoundingMode.HALF_UP);
                    } else {
                        currentCumulative = currentCumulative.add(rawValue).setScale(3, RoundingMode.HALF_UP);
                    }
                    runningCumulativeForDeltas.put(spec.meterName(), currentCumulative);
                    valueToRecord = currentCumulative;
                } else {
                    valueToRecord = rawValue.setScale(3, RoundingMode.HALF_UP);
                }

                batchValues.put(meter.getId(), valueToRecord);
            }

            if (!batchValues.isEmpty()) {
                List<MeterReading> saved = readingService.recordBatchReadings(
                        row.date(), batchValues, "ODS-Import");
                readingsSaved += saved.size();
                datesProcessed++;
            }
        }

        return new OdsImportResult(
                assetsByName.size(),
                metersByName.size(),
                tariffsCreated,
                datesProcessed,
                readingsSaved);
    }

    static List<BigDecimal> accumulateDeltas(List<BigDecimal> deltas) {
        List<BigDecimal> result = new ArrayList<>(deltas.size());
        BigDecimal sum = BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP);
        for (BigDecimal delta : deltas) {
            if (delta != null) {
                sum = sum.add(delta).setScale(3, RoundingMode.HALF_UP);
            }
            result.add(sum);
        }
        return result;
    }

    public static ParsedOdsData parseOds(InputStream odsStream) {
        Document contentXml = readContentXml(odsStream);
        NodeList tables = contentXml.getElementsByTagNameNS(TABLE_NS, "table");
        if (tables.getLength() == 0) {
            throw new IllegalArgumentException("No spreadsheet table found in ODS file");
        }

        Element sheet = (Element) tables.item(0);
        NodeList rowNodes = sheet.getChildNodes();

        BigDecimal priceQ1 = new BigDecimal("0.2600");
        BigDecimal feedInR1 = new BigDecimal("0.0703");
        BigDecimal gridImportS1 = new BigDecimal("0.3400");
        List<ParsedRow> parsedRows = new ArrayList<>();

        int rowIndex = 0;
        for (int i = 0; i < rowNodes.getLength(); i++) {
            Node node = rowNodes.item(i);
            if (!(node instanceof Element rowElement) || !"table-row".equals(rowElement.getLocalName())) {
                continue;
            }
            List<Element> expandedCells = expandRowCells(rowElement, 30);

            if (rowIndex == 0) {
                priceQ1 = parseNumericCell(expandedCells, 16, priceQ1);
                feedInR1 = parseNumericCell(expandedCells, 17, feedInR1);
                gridImportS1 = parseNumericCell(expandedCells, 18, gridImportS1);
            } else if (rowIndex >= 5) {
                LocalDate date = parseDateCell(expandedCells, 0);
                if (date != null) {
                    Map<String, BigDecimal> values = new LinkedHashMap<>();
                    for (Map.Entry<Integer, ColumnSpec> entry : ODS_METER_COLUMNS.entrySet()) {
                        BigDecimal cellVal = parseNumericCell(expandedCells, entry.getKey(), null);
                        if (cellVal != null) {
                            values.put(entry.getValue().meterName(), cellVal);
                        }
                    }
                    if (!values.isEmpty()) {
                        parsedRows.add(new ParsedRow(date, values));
                    }
                }
            }
            rowIndex++;
        }

        return new ParsedOdsData(priceQ1, feedInR1, gridImportS1, parsedRows);
    }

    private Map<String, EnergyAsset> ensureAssets(OdsAssetCostDefaults defaults) {
        Map<String, EnergyAsset> existing = new LinkedHashMap<>();
        for (EnergyAsset asset : assetService.findAll()) {
            existing.put(asset.getName(), asset);
        }

        ensureSingleAsset(existing, ASSET_PV_DACH, LocalDate.of(2024, 7, 11),
                defaults != null && defaults.pvDachCost() != null ? defaults.pvDachCost() : new BigDecimal("12000.00"));
        ensureSingleAsset(existing, ASSET_PV_GARTENHAUS, LocalDate.of(2024, 1, 1),
                defaults != null && defaults.pvGartenhausCost() != null ? defaults.pvGartenhausCost() : new BigDecimal("1500.00"));
        ensureSingleAsset(existing, ASSET_WALLBOX, LocalDate.of(2024, 1, 1),
                defaults != null && defaults.wallboxCost() != null ? defaults.wallboxCost() : new BigDecimal("1200.00"));

        return existing;
    }

    private void ensureSingleAsset(
            Map<String, EnergyAsset> map, String name, LocalDate installationDate, BigDecimal defaultCost) {
        EnergyAsset asset = map.get(name);
        if (asset == null) {
            asset = new EnergyAsset();
            asset.setName(name);
            asset.setInstallationDate(installationDate);
            asset.setInvestmentCost(defaultCost);
            asset.setInitialAmortized(BigDecimal.ZERO);
            asset = assetService.save(asset);
            map.put(name, asset);
        } else if (defaultCost != null && defaultCost.signum() > 0) {
            asset.setInvestmentCost(defaultCost);
            asset = assetService.save(asset);
            map.put(name, asset);
        }
    }

    private Map<String, Meter> ensureMeters(Map<String, EnergyAsset> assetsByName) {
        Map<String, Meter> existing = new LinkedHashMap<>();
        for (Meter meter : meterService.findAll()) {
            existing.put(meter.getName(), meter);
        }

        for (ColumnSpec spec : ODS_METER_COLUMNS.values()) {
            if (!existing.containsKey(spec.meterName())) {
                Meter meter = new Meter();
                meter.setName(spec.meterName());
                meter.setType(spec.type());
                meter.setUnit(spec.unit());
                meter.setDisplayOrder(spec.displayOrder());
                meter.setOverflowThreshold(spec.overflowThreshold());
                meter.setConversionFactor(BigDecimal.ONE);
                meter.setActive(true);
                if (spec.assetName() != null && assetsByName.containsKey(spec.assetName())) {
                    meter.setAssetId(assetsByName.get(spec.assetName()).getId());
                }
                existing.put(spec.meterName(), meterService.save(meter));
            }
        }
        return existing;
    }

    private int ensureTariffs(ParsedOdsData parsed) {
        int created = 0;
        LocalDate validFrom = LocalDate.of(2024, 1, 1);

        if (tariffService.findByMeterType(MeterType.GRID_IMPORT).isEmpty()) {
            EnergyTariff importTariff = new EnergyTariff();
            importTariff.setName("Strombezug (ODS)");
            importTariff.setForMeterType(MeterType.GRID_IMPORT);
            importTariff.setValidFrom(validFrom);
            importTariff.setUnitPriceEur(parsed.gridImportTariffS1());
            importTariff.setMonthlyBaseFeeEur(BigDecimal.ZERO);
            tariffService.save(importTariff);
            created++;
        }

        if (tariffService.findByMeterType(MeterType.GRID_EXPORT).isEmpty()) {
            EnergyTariff exportTariff = new EnergyTariff();
            exportTariff.setName("EEG Einspeisevergütung (ODS)");
            exportTariff.setForMeterType(MeterType.GRID_EXPORT);
            exportTariff.setValidFrom(validFrom);
            exportTariff.setUnitPriceEur(parsed.feedInTariffR1());
            exportTariff.setMonthlyBaseFeeEur(BigDecimal.ZERO);
            tariffService.save(exportTariff);
            created++;
        }

        if (tariffService.findByMeterType(MeterType.WALLBOX_COMPANY).isEmpty()) {
            EnergyTariff wbTariff = new EnergyTariff();
            wbTariff.setName("AG-Erstattung Dienstwagen (ODS)");
            wbTariff.setForMeterType(MeterType.WALLBOX_COMPANY);
            wbTariff.setValidFrom(validFrom);
            wbTariff.setUnitPriceEur(parsed.gridImportTariffS1());
            wbTariff.setMonthlyBaseFeeEur(BigDecimal.ZERO);
            tariffService.save(wbTariff);
            created++;
        }

        return created;
    }

    private static Document readContentXml(InputStream odsStream) {
        try (ZipInputStream zis = new ZipInputStream(odsStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("content.xml".equals(entry.getName())) {
                    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                    factory.setNamespaceAware(true);
                    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                    DocumentBuilder builder = factory.newDocumentBuilder();
                    return builder.parse(zis);
                }
            }
        } catch (Exception exception) {
            throw new IllegalArgumentException("Failed to read ODS file: " + exception.getMessage(), exception);
        }
        throw new IllegalArgumentException("Invalid ODS file: content.xml not found");
    }

    private static List<Element> expandRowCells(Element rowElement, int maxColumns) {
        List<Element> expanded = new ArrayList<>(maxColumns);
        NodeList children = rowElement.getChildNodes();
        for (int i = 0; i < children.getLength() && expanded.size() < maxColumns; i++) {
            Node child = children.item(i);
            if (!(child instanceof Element cell) || !"table-cell".equals(child.getLocalName())) {
                continue;
            }
            String repAttr = cell.getAttributeNS(TABLE_NS, "number-columns-repeated");
            int repeat = 1;
            if (repAttr != null && !repAttr.isBlank()) {
                repeat = Math.min(Integer.parseInt(repAttr), maxColumns - expanded.size());
            }
            for (int r = 0; r < repeat && expanded.size() < maxColumns; r++) {
                expanded.add(cell);
            }
        }
        return expanded;
    }

    private static LocalDate parseDateCell(List<Element> cells, int index) {
        if (index >= cells.size()) {
            return null;
        }
        Element cell = cells.get(index);
        String dateAttr = cell.getAttributeNS(OFFICE_NS, "date-value");
        if (dateAttr != null && !dateAttr.isBlank()) {
            return LocalDate.parse(dateAttr.substring(0, 10));
        }
        String text = cell.getTextContent();
        if (text != null && !text.isBlank()) {
            try {
                return LocalDate.parse(text.trim(), GERMAN_DATE);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static BigDecimal parseNumericCell(List<Element> cells, int index, BigDecimal fallback) {
        if (index >= cells.size()) {
            return fallback;
        }
        Element cell = cells.get(index);
        String valueAttr = cell.getAttributeNS(OFFICE_NS, "value");
        if (valueAttr != null && !valueAttr.isBlank()) {
            try {
                return new BigDecimal(valueAttr.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private record ColumnSpec(
            String meterName,
            MeterType type,
            String unit,
            int displayOrder,
            BigDecimal overflowThreshold,
            String assetName,
            boolean deltaSeries) {
    }

    public record ParsedRow(LocalDate date, Map<String, BigDecimal> valuesByColumn) {
    }

    public record ParsedOdsData(
            BigDecimal priceQ1,
            BigDecimal feedInTariffR1,
            BigDecimal gridImportTariffS1,
            List<ParsedRow> rows) {
    }

    public record OdsAssetCostDefaults(
            BigDecimal pvDachCost,
            BigDecimal pvGartenhausCost,
            BigDecimal wallboxCost) {
    }

    public record OdsImportResult(
            int assetsConfigured,
            int metersConfigured,
            int tariffsCreated,
            int readingDatesImported,
            int totalReadingsSaved) {
    }
}

