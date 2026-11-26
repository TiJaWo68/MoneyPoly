package de.in.mp.energy.sync;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Shared DTOs returned by energy device clients and sync orchestration.
 */
public final class EnergyDeviceModels {

    private EnergyDeviceModels() {
    }

    public record DeviceSnapshot(
            Map<String, BigDecimal> absoluteKwhByMetric,
            List<ChargingSession> sessions,
            Map<String, String> tagLabels) {

        public static DeviceSnapshot ofMetrics(Map<String, BigDecimal> metrics) {
            return new DeviceSnapshot(metrics, List.of(), Map.of());
        }
    }

    public record ChargingSession(long remoteId, String userTag, BigDecimal energyKwh, LocalDate stopDate) {
        public ChargingSession(long remoteId, String userTag, BigDecimal energyKwh) {
            this(remoteId, userTag, energyKwh, null);
        }
    }

    public record EnergySyncSummary(int sourcesChecked, int readingsUpdated, List<String> errors) {
    }
}
