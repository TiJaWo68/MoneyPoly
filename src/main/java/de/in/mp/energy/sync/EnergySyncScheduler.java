package de.in.mp.energy.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import de.in.mp.energy.sync.EnergyDeviceModels.EnergySyncSummary;

/**
 * Scheduled job fetching automated energy meter readings (default on the last
 * day of each month at 23:55 so clean monthly end-of-month readings are captured).
 */
@Component
@ConditionalOnProperty(
        name = "moneypoly.energysync.scheduling.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class EnergySyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(EnergySyncScheduler.class);

    private final EnergySyncService energySyncService;

    public EnergySyncScheduler(EnergySyncService energySyncService) {
        this.energySyncService = energySyncService;
    }

    @Scheduled(cron = "${moneypoly.energysync.cron:0 55 23 L * *}")
    public void runMonthlyEnergySync() {
        log.info("Starting scheduled energy meter sync");
        EnergySyncSummary summary = energySyncService.syncAllAutoEnabled();
        log.info("Completed scheduled energy meter sync: sources={}, updated={}, errors={}",
                summary.sourcesChecked(), summary.readingsUpdated(), summary.errors().size());
    }
}
