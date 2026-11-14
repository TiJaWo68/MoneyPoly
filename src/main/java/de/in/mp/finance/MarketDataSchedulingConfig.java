package de.in.mp.finance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Enables daily market price updates for position-based investment depots after
 * European and US market close. Disabled cleanly in tests by setting
 * {@code moneypoly.market-data.enabled=false}.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "moneypoly.market-data.enabled", havingValue = "true", matchIfMissing = true)
public class MarketDataSchedulingConfig {

    private static final Logger LOG = LoggerFactory.getLogger(MarketDataSchedulingConfig.class);

    private final PortfolioPositionService portfolioPositionService;

    public MarketDataSchedulingConfig(PortfolioPositionService portfolioPositionService) {
        this.portfolioPositionService = portfolioPositionService;
    }

    @Scheduled(cron = "${moneypoly.market-data.cron:0 30 22 * * MON-FRI}")
    public void refreshPortfolioPrices() {
        int updated = portfolioPositionService.refreshAllPrices();
        LOG.info("Scheduled portfolio market price refresh updated {} positions", updated);
    }
}
