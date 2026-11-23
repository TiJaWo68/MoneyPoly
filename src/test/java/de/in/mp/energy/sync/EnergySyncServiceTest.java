package de.in.mp.energy.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.in.mp.energy.Meter;
import de.in.mp.energy.MeterReading;
import de.in.mp.energy.MeterReadingService;
import de.in.mp.energy.MeterRepository;
import de.in.mp.energy.MeterType;
import de.in.mp.energy.sync.EnergyDeviceModels.ChargingSession;
import de.in.mp.energy.sync.EnergyDeviceModels.DeviceSnapshot;
import de.in.mp.energy.sync.EnergyDeviceModels.EnergySyncSummary;
import de.in.mp.finance.banksync.SessionCryptoService;

class EnergySyncServiceTest {

    private EnergyDataSourceRepository dataSourceRepository;
    private MeterRepository meterRepository;
    private MeterReadingService readingService;
    private ShellyPro3EmClient shellyClient;
    private FusionSolarKioskClient kioskClient;
    private KathreinWallboxClient wallboxClient;
    private SessionCryptoService cryptoService;
    private EnergySyncService service;

    @BeforeEach
    void setUp() {
        dataSourceRepository = mock(EnergyDataSourceRepository.class);
        meterRepository = mock(MeterRepository.class);
        readingService = mock(MeterReadingService.class);
        shellyClient = mock(ShellyPro3EmClient.class);
        kioskClient = mock(FusionSolarKioskClient.class);
        wallboxClient = mock(KathreinWallboxClient.class);
        cryptoService = new SessionCryptoService("dGVzdC1rZXktMzItYnl0ZXMtbG9uZy0xMjM0NTY3ODkw");
        service = new EnergySyncService(
                dataSourceRepository, meterRepository, readingService,
                shellyClient, kioskClient, wallboxClient, cryptoService);
    }

    @Test
    void calibratesOffsetAndSyncsShellyImportExportMeters() throws Exception {
        UUID sourceId = UUID.randomUUID();
        UUID houseId = UUID.randomUUID();
        EnergyDataSource shelly = new EnergyDataSource();
        shelly.setId(sourceId);
        shelly.setHouseId(houseId);
        shelly.setName("Shelly Pro 3EM");
        shelly.setProvider(EnergyDataSourceProvider.SHELLY_PRO_3EM);
        shelly.setBaseUrl("http://192.168.178.132");
        shelly.setEnabled(true);

        when(dataSourceRepository.findById(sourceId)).thenReturn(Optional.of(shelly));
        when(shellyClient.fetch(shelly)).thenReturn(DeviceSnapshot.ofMetrics(Map.of(
                ShellyPro3EmClient.METRIC_TOTAL_ACT, new BigDecimal("8705.850"),
                ShellyPro3EmClient.METRIC_TOTAL_ACT_RET, new BigDecimal("19524.700"))));

        // Official meter was at 24919.000 -> offset = 24919.000 - 8705.850 = 16213.150
        BigDecimal offset = service.calibrateOffset(
                sourceId, ShellyPro3EmClient.METRIC_TOTAL_ACT, new BigDecimal("24919.000"));
        assertThat(offset).isEqualByComparingTo("16213.150");

        Meter importMeter = new Meter();
        importMeter.setId(UUID.randomUUID());
        importMeter.setName("Strom Gesamt");
        importMeter.setType(MeterType.GRID_IMPORT);
        importMeter.setActive(true);
        importMeter.setDataSourceId(sourceId);
        importMeter.setSourceMetricKey(ShellyPro3EmClient.METRIC_TOTAL_ACT);
        importMeter.setSourceOffset(offset);

        MeterReading prev = new MeterReading();
        prev.setReadingDate(LocalDate.of(2026, 10, 31));
        prev.setReadingValue(new BigDecimal("24919.000"));
        when(readingService.findByMeter(importMeter.getId())).thenReturn(List.of(prev));
        when(meterRepository.findByDataSourceId(sourceId)).thenReturn(List.of(importMeter));
        when(shellyClient.fetch(shelly)).thenReturn(DeviceSnapshot.ofMetrics(Map.of(
                ShellyPro3EmClient.METRIC_TOTAL_ACT, new BigDecimal("8805.850"))));

        LocalDate today = LocalDate.of(2026, 11, 23);
        EnergySyncSummary summary = service.syncSources(List.of(shelly), today);

        assertThat(summary.sourcesChecked()).isEqualTo(1);
        assertThat(summary.readingsUpdated()).isEqualTo(1);
        assertThat(summary.errors()).isEmpty();
        verify(readingService).recordReading(
                importMeter.getId(), today, new BigDecimal("25019.000"), "Auto-Sync: Shelly Pro 3EM");
    }

    @Test
    void syncsKathreinWallboxRfidSessionsWithoutDoubleCounting() throws Exception {
        UUID sourceId = UUID.randomUUID();
        EnergyDataSource wallbox = new EnergyDataSource();
        wallbox.setId(sourceId);
        wallbox.setHouseId(UUID.randomUUID());
        wallbox.setName("Kathrein Wallbox");
        wallbox.setProvider(EnergyDataSourceProvider.KATHREIN_WALLBOX);
        wallbox.setBaseUrl("https://192.168.178.181");
        wallbox.setUsername("operator");
        wallbox.setSecretEncrypted(cryptoService.encrypt("secret"));
        wallbox.setEnabled(true);

        Meter id4Meter = new Meter();
        id4Meter.setId(UUID.randomUUID());
        id4Meter.setName("Wallbox ID4");
        id4Meter.setType(MeterType.WALLBOX_COMPANY);
        id4Meter.setActive(true);
        id4Meter.setDataSourceId(sourceId);
        id4Meter.setSourceMetricKey("9333A680");
        id4Meter.setLastRemoteId(395L);

        MeterReading prev = new MeterReading();
        prev.setReadingDate(LocalDate.of(2026, 11, 20));
        prev.setReadingValue(new BigDecimal("1000.000"));

        when(meterRepository.findByDataSourceId(sourceId)).thenReturn(List.of(id4Meter));
        when(readingService.findByMeter(id4Meter.getId())).thenReturn(List.of(prev));
        when(wallboxClient.fetch(wallbox, "secret")).thenReturn(new DeviceSnapshot(
                Map.of(),
                List.of(
                        new ChargingSession(398L, "9333A680", new BigDecimal("23.376")),
                        new ChargingSession(397L, "04968ADA551390", new BigDecimal("23.570")),
                        new ChargingSession(395L, "9333A680", new BigDecimal("51.714"))),
                Map.of()));

        LocalDate today = LocalDate.of(2026, 11, 23);
        EnergySyncSummary first = service.syncSources(List.of(wallbox), today);

        assertThat(first.readingsUpdated()).isEqualTo(1);
        assertThat(id4Meter.getLastRemoteId()).isEqualTo(398L);
        verify(readingService).recordReading(
                id4Meter.getId(), today, new BigDecimal("1023.376"), "Auto-Sync: Kathrein Wallbox");

        // Second sync with same sessions must not add anything again
        EnergySyncSummary second = service.syncSources(List.of(wallbox), today);
        assertThat(second.readingsUpdated()).isEqualTo(0);
        verify(readingService, never()).recordReading(
                id4Meter.getId(), today, new BigDecimal("1046.752"), "Auto-Sync: Kathrein Wallbox");
    }

    @Test
    void encryptsSecretWhenSavingDataSource() {
        EnergyDataSource source = new EnergyDataSource();
        source.setHouseId(UUID.randomUUID());
        source.setName("Kiosk");
        source.setProvider(EnergyDataSourceProvider.FUSIONSOLAR_KIOSK);
        source.setBaseUrl("https://uni003eu5.fusionsolar.huawei.com/");
        when(dataSourceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EnergyDataSource saved = service.saveDataSource(source, "kk-secret-123");

        assertThat(saved.getBaseUrl()).isEqualTo("https://uni003eu5.fusionsolar.huawei.com");
        assertThat(saved.getSecretEncrypted()).isNotBlank().doesNotContain("kk-secret-123");
        assertThat(cryptoService.decrypt(saved.getSecretEncrypted())).isEqualTo("kk-secret-123");
    }
}
