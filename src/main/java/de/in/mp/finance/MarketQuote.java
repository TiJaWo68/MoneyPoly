package de.in.mp.finance;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Market quote returned by a {@link MarketPriceProvider} in the security's
 * trading currency.
 */
public record MarketQuote(String symbol, BigDecimal price, String currency, Instant fetchedAt) {
}
