package de.in.mp.finance.banksync;

import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Enables nightly read-only Open-Banking synchronization for accounts whose
 * linked {@link BankSyncProvider} supports unattended background syncs.
 * Disabled cleanly in tests via {@code moneypoly.banksync.scheduling.enabled=false}.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "moneypoly.banksync.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class BankSyncSchedulingConfig {

    private static final Logger LOG = LoggerFactory.getLogger(BankSyncSchedulingConfig.class);

    private final BankSyncService bankSyncService;

    public BankSyncSchedulingConfig(BankSyncService bankSyncService) {
        this.bankSyncService = bankSyncService;
    }

    @Scheduled(cron = "${moneypoly.banksync.cron:0 0 3 * * *}")
    public void syncLinkedAccounts() {
        BankSyncResult result = bankSyncService.runScheduledSync(LocalDate.now());
        LOG.info("Scheduled Open-Banking sync finished: {} synced, {} skipped, {} failed",
                result.syncedAccounts(), result.skippedAccounts(), result.failedAccounts());
    }
}
