package de.in.mp.finance;

import java.util.Optional;

/**
 * Provider abstraction for fetching latest market prices and resolving ISINs to
 * exchange ticker symbols.
 */
public interface MarketPriceProvider {

    Optional<MarketQuote> fetchQuote(String symbol);

    Optional<String> resolveTickerByIsin(String isin);
}
