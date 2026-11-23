package de.in.mp.energy.sync;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.in.mp.energy.Meter;
import de.in.mp.energy.MeterReading;
import de.in.mp.energy.MeterReadingService;
import de.in.mp.energy.MeterRepository;
import de.in.mp.energy.sync.EnergyDeviceModels.ChargingSession;
import de.in.mp.energy.sync.EnergyDeviceModels.DeviceSnapshot;
import de.in.mp.energy.sync.EnergyDeviceModels.EnergySyncSummary;
import de.in.mp.finance.banksync.SessionCryptoService;

/**
 * Coordinates configuration, connection testing, offset calibration, and
 * automated meter reading synchronization from local and cloud energy devices.
 */
@Service
public class EnergySyncService {

    private static final Logger log = LoggerFactory.getLogger(EnergySyncService.class);

    private final EnergyDataSourceRepository dataSourceRepository;
    private final MeterRepository meterRepository;
    private final MeterReadingService readingService;
    private final ShellyPro3EmClient shellyClient;
    private final FusionSolarKioskClient kioskClient;
    private final KathreinWallboxClient wallboxClient;
    private final SessionCryptoService cryptoService;

    @Autowired
    public EnergySyncService(
            EnergyDataSourceRepository dataSourceRepository,
            MeterRepository meterRepository,
            MeterReadingService readingService,
            ShellyPro3EmClient shellyClient,
            FusionSolarKioskClient kioskClient,
            KathreinWallboxClient wallboxClient,
            @Autowired(required = false) SessionCryptoService cryptoService) {
        this.dataSourceRepository = dataSourceRepository;
        this.meterRepository = meterRepository;
        this.readingService = readingService;
        this.shellyClient = shellyClient;
        this.kioskClient = kioskClient;
        this.wallboxClient = wallboxClient;
        this.cryptoService = cryptoService;
    }

    @Transactional(readOnly = true)
    public List<EnergyDataSource> findByHouse(UUID houseId) {
        if (houseId == null) {
            return List.of();
        }
        return dataSourceRepository.findByHouseIdOrderByNameAsc(houseId);
    }

    @Transactional(readOnly = true)
    public Optional<EnergyDataSource> findById(UUID sourceId) {
        if (sourceId == null) {
            return Optional.empty();
        }
        return dataSourceRepository.findById(sourceId);
    }

    @Transactional
    public EnergyDataSource saveDataSource(EnergyDataSource source, String rawSecretOverride) {
        if (source.getHouseId() == null) {
            throw new IllegalArgumentException("House ID is required");
        }
        if (source.getName() == null || source.getName().isBlank()) {
            throw new IllegalArgumentException("Name is required");
        }
        if (source.getProvider() == null) {
            throw new IllegalArgumentException("Provider is required");
        }
        if (source.getBaseUrl() == null || source.getBaseUrl().isBlank()) {
            throw new IllegalArgumentException("Base URL is required");
        }
        source.setName(source.getName().trim());
        source.setBaseUrl(EnergyHttpHelper.normalizeBaseUrl(source.getBaseUrl()));
        source.setUsername(source.getUsername() == null || source.getUsername().isBlank()
                ? null
                : source.getUsername().trim());

        if (source.getProvider().usesSecret()) {
            if (rawSecretOverride != null && !rawSecretOverride.isBlank()) {
                if (cryptoService == null || !cryptoService.isConfigured()) {
                    throw new IllegalStateException("MONEYPOLY_CRYPTO_KEY is required to store device secrets");
                }
                source.setSecretEncrypted(cryptoService.encrypt(rawSecretOverride.trim()));
            } else if (source.getSecretEncrypted() == null || source.getSecretEncrypted().isBlank()) {
                throw new IllegalArgumentException("Secret / API key is required for this provider");
            }
        } else {
            source.setSecretEncrypted(null);
        }
        return dataSourceRepository.save(source);
    }

    @Transactional
    public void deleteDataSource(UUID sourceId) {
        if (sourceId != null) {
            dataSourceRepository.deleteById(sourceId);
        }
    }

    public String testConnection(EnergyDataSource source, String rawSecretOverride)
            throws IOException, InterruptedException {
        String secret = resolveSecret(source, rawSecretOverride);
        DeviceSnapshot snapshot = fetchSnapshot(source, secret);
        if (source.getProvider() == EnergyDataSourceProvider.KATHREIN_WALLBOX) {
            Set<String> tags = snapshot.sessions().stream()
                    .map(ChargingSession::userTag)
                    .collect(Collectors.toCollection(java.util.TreeSet::new));
            long maxId = snapshot.sessions().stream().mapToLong(ChargingSession::remoteId).max().orElse(0L);
            String tagSummary = tags.stream()
                    .map(tag -> snapshot.tagLabels().containsKey(tag)
                            ? tag + " (" + snapshot.tagLabels().get(tag) + ")"
                            : tag)
                    .collect(Collectors.joining(", "));
            return "OK · " + snapshot.sessions().size() + " Ladevorgänge (max ID " + maxId + ")"
                    + (tagSummary.isBlank() ? "" : " · RFID: " + tagSummary);
        }
        return "OK · " + snapshot.absoluteKwhByMetric().entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue().toPlainString() + " kWh")
                .collect(Collectors.joining(" · "));
    }

    public BigDecimal calibrateOffset(UUID dataSourceId, String metricKey, BigDecimal latestMeterReading)
            throws IOException, InterruptedException {
        if (dataSourceId == null || metricKey == null || metricKey.isBlank() || latestMeterReading == null) {
            throw new IllegalArgumentException("Data source, metric key, and current meter reading are required");
        }
        EnergyDataSource source = dataSourceRepository.findById(dataSourceId)
                .orElseThrow(() -> new IllegalArgumentException("Data source not found: " + dataSourceId));
        if (source.getProvider() == EnergyDataSourceProvider.KATHREIN_WALLBOX) {
            return BigDecimal.ZERO;
        }
        DeviceSnapshot snapshot = fetchSnapshot(source, resolveSecret(source, null));
        BigDecimal deviceValue = snapshot.absoluteKwhByMetric().get(metricKey.trim());
        if (deviceValue == null) {
            throw new IllegalArgumentException("Metric not available from device: " + metricKey);
        }
        return latestMeterReading.subtract(deviceValue).setScale(3, RoundingMode.HALF_UP);
    }

    public long fetchLatestWallboxRemoteId(UUID dataSourceId) throws IOException, InterruptedException {
        EnergyDataSource source = dataSourceRepository.findById(dataSourceId)
                .orElseThrow(() -> new IllegalArgumentException("Data source not found: " + dataSourceId));
        if (source.getProvider() != EnergyDataSourceProvider.KATHREIN_WALLBOX) {
            return 0L;
        }
        DeviceSnapshot snapshot = fetchSnapshot(source, resolveSecret(source, null));
        return snapshot.sessions().stream().mapToLong(ChargingSession::remoteId).max().orElse(0L);
    }

    @Transactional
    public EnergySyncSummary syncHouse(UUID houseId) {
        if (houseId == null) {
            return new EnergySyncSummary(0, 0, List.of());
        }
        return syncSources(dataSourceRepository.findByHouseIdAndEnabledTrue(houseId), LocalDate.now());
    }

    @Transactional
    public EnergySyncSummary syncAllAutoEnabled() {
        return syncSources(dataSourceRepository.findByEnabledTrueAndAutoSyncEnabledTrue(), LocalDate.now());
    }

    EnergySyncSummary syncSources(List<EnergyDataSource> sources, LocalDate readingDate) {
        int checked = 0;
        int updated = 0;
        List<String> errors = new ArrayList<>();

        for (EnergyDataSource source : sources) {
            checked++;
            try {
                DeviceSnapshot snapshot = fetchSnapshot(source, resolveSecret(source, null));
                List<Meter> linkedMeters = meterRepository.findByDataSourceId(source.getId()).stream()
                        .filter(Meter::isActive)
                        .toList();
                int sourceUpdates = 0;
                for (Meter meter : linkedMeters) {
                    if (applySnapshotToMeter(source, meter, snapshot, readingDate)) {
                        sourceUpdates++;
                        updated++;
                    }
                }
                source.setLastSyncedAt(OffsetDateTime.now());
                source.setLastSyncStatus("OK (" + sourceUpdates + " Zähler aktualisiert)");
                dataSourceRepository.save(source);
            } catch (Exception exception) {
                String msg = source.getName() + ": " + exception.getMessage();
                log.warn("Energy sync failed for source {}: {}", source.getName(), exception.getMessage());
                errors.add(msg);
                source.setLastSyncedAt(OffsetDateTime.now());
                source.setLastSyncStatus(truncate("Fehler: " + exception.getMessage(), 250));
                dataSourceRepository.save(source);
            }
        }
        return new EnergySyncSummary(checked, updated, errors);
    }

    private boolean applySnapshotToMeter(
            EnergyDataSource source, Meter meter, DeviceSnapshot snapshot, LocalDate readingDate) {
        String metricKey = meter.getSourceMetricKey();
        if (metricKey == null || metricKey.isBlank()) {
            return false;
        }
        List<MeterReading> timeline = readingService.findByMeter(meter.getId());
        Optional<MeterReading> latestBeforeToday = timeline.stream()
                .filter(r -> r.getReadingDate().isBefore(readingDate))
                .reduce((first, second) -> second);

        if (source.getProvider() == EnergyDataSourceProvider.KATHREIN_WALLBOX) {
            long minRemoteId = meter.getLastRemoteId() != null ? meter.getLastRemoteId() : 0L;
            BigDecimal addedKwh = BigDecimal.ZERO;
            long maxRemoteId = minRemoteId;
            for (ChargingSession session : snapshot.sessions()) {
                if (session.remoteId() > minRemoteId && metricKey.trim().equalsIgnoreCase(session.userTag())) {
                    addedKwh = addedKwh.add(session.energyKwh());
                    if (session.remoteId() > maxRemoteId) {
                        maxRemoteId = session.remoteId();
                    }
                }
            }
            if (maxRemoteId <= minRemoteId || addedKwh.signum() <= 0) {
                return false;
            }
            BigDecimal currentBase = timeline.isEmpty()
                    ? BigDecimal.ZERO
                    : timeline.get(timeline.size() - 1).getReadingValue();
            BigDecimal nextReading = currentBase.add(addedKwh).setScale(3, RoundingMode.HALF_UP);
            readingService.recordReading(meter.getId(), readingDate, nextReading, "Auto-Sync: " + source.getName());
            meter.setLastRemoteId(maxRemoteId);
            meterRepository.save(meter);
            return true;
        }

        BigDecimal rawKwh = snapshot.absoluteKwhByMetric().get(metricKey.trim());
        if (rawKwh == null) {
            return false;
        }
        BigDecimal offset = meter.getSourceOffset() != null ? meter.getSourceOffset() : BigDecimal.ZERO;
        BigDecimal nextReading = offset.add(rawKwh).setScale(3, RoundingMode.HALF_UP);
        if (latestBeforeToday.isPresent() && nextReading.compareTo(latestBeforeToday.get().getReadingValue()) < 0) {
            throw new IllegalStateException("Neuer Wert " + nextReading + " liegt unter Vortagsstand "
                    + latestBeforeToday.get().getReadingValue() + " (" + meter.getName() + ")");
        }
        readingService.recordReading(meter.getId(), readingDate, nextReading, "Auto-Sync: " + source.getName());
        return true;
    }

    private DeviceSnapshot fetchSnapshot(EnergyDataSource source, String rawSecret)
            throws IOException, InterruptedException {
        return switch (source.getProvider()) {
            case SHELLY_PRO_3EM -> shellyClient.fetch(source);
            case FUSIONSOLAR_KIOSK -> kioskClient.fetch(source, rawSecret);
            case KATHREIN_WALLBOX -> wallboxClient.fetch(source, rawSecret);
        };
    }

    private String resolveSecret(EnergyDataSource source, String rawSecretOverride) {
        if (rawSecretOverride != null && !rawSecretOverride.isBlank()) {
            return rawSecretOverride.trim();
        }
        if (!source.getProvider().usesSecret() || source.getSecretEncrypted() == null) {
            return null;
        }
        if (cryptoService == null || !cryptoService.isConfigured()) {
            throw new IllegalStateException("MONEYPOLY_CRYPTO_KEY is not configured");
        }
        return cryptoService.decrypt(source.getSecretEncrypted());
    }

    private static String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen - 3) + "...";
    }
}
