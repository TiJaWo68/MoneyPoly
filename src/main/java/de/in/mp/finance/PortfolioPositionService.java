package de.in.mp.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages {@link PortfolioPosition} holdings inside {@link AccountType#INVESTMENT}
 * depot accounts and implements the dual valuation rule:
 * <ul>
 *   <li><b>Mode A (positions present)</b>: depot balance and cost-basis performance
 *       are computed from positions, updated via {@link MarketPriceProvider} and
 *       converted to EUR via {@link EcbExchangeRateService}.</li>
 *   <li><b>Mode B (no positions)</b>: falls back to the latest manual
 *       {@link AccountSnapshot}.</li>
 * </ul>
 */
@Service
public class PortfolioPositionService {

    private static final Logger LOG = LoggerFactory.getLogger(PortfolioPositionService.class);

    private static final int MONEY_SCALE = 2;
    private static final int PRICE_SCALE = 4;
    private static final String YAHOO_FX_SUFFIX = "EUR=X";

    private final PortfolioPositionRepository positionRepository;
    private final AccountRepository accountRepository;
    private final AccountSnapshotRepository snapshotRepository;
    private final MarketPriceProvider marketPriceProvider;
    private final EcbExchangeRateService exchangeRateService;
    private final HouseholdService householdService;

    @Autowired
    public PortfolioPositionService(
            PortfolioPositionRepository positionRepository,
            AccountRepository accountRepository,
            AccountSnapshotRepository snapshotRepository,
            MarketPriceProvider marketPriceProvider,
            EcbExchangeRateService exchangeRateService,
            @Autowired(required = false) HouseholdService householdService) {
        this.positionRepository = positionRepository;
        this.accountRepository = accountRepository;
        this.snapshotRepository = snapshotRepository;
        this.marketPriceProvider = marketPriceProvider;
        this.exchangeRateService = exchangeRateService;
        this.householdService = householdService;
    }

    @Transactional(readOnly = true)
    public List<PortfolioPosition> findByAccount(UUID accountId) {
        if (accountId == null) {
            return List.of();
        }
        return positionRepository.findByAccountIdOrderByNameAsc(accountId);
    }

    @Transactional(readOnly = true)
    public boolean hasPositions(UUID accountId) {
        return accountId != null && positionRepository.existsByAccountId(accountId);
    }

    @Transactional(readOnly = true)
    public DepotValuation resolveValuation(Account account, AccountSnapshot latestSnapshot) {
        if (account == null || account.getId() == null) {
            return new DepotValuation(null, false, BigDecimal.ZERO, null, null, false, 0);
        }
        return resolveValuations(List.of(account), latestSnapshot == null
                ? Map.of()
                : Map.of(account.getId(), latestSnapshot)).get(account.getId());
    }

    @Transactional(readOnly = true)
    public Map<UUID, DepotValuation> resolveValuations(
            Collection<Account> investmentAccounts,
            Map<UUID, AccountSnapshot> latestSnapshots) {
        Map<UUID, DepotValuation> result = new HashMap<>();
        if (investmentAccounts == null || investmentAccounts.isEmpty()) {
            return result;
        }
        List<UUID> accountIds = investmentAccounts.stream()
                .map(Account::getId)
                .filter(id -> id != null)
                .toList();
        Map<UUID, List<PortfolioPosition>> positionsByAccount = accountIds.isEmpty()
                ? Map.of()
                : positionRepository.findByAccountIdIn(accountIds).stream()
                        .collect(Collectors.groupingBy(PortfolioPosition::getAccountId));

        for (Account account : investmentAccounts) {
            UUID id = account.getId();
            if (id == null) {
                continue;
            }
            List<PortfolioPosition> positions = positionsByAccount.getOrDefault(id, List.of());
            AccountSnapshot snapshot = latestSnapshots != null ? latestSnapshots.get(id) : null;
            result.put(id, positions.isEmpty()
                    ? buildSnapshotValuation(account, snapshot)
                    : buildPositionValuation(id, positions));
        }
        return result;
    }

    @Transactional
    public PortfolioPosition save(PortfolioPosition position) {
        if (position == null) {
            throw new IllegalArgumentException("Position must not be null");
        }
        Account depot = requireInvestmentAccount(position.getAccountId());
        validatePosition(position);
        normalizeFields(position);

        if (position.isManualPriceOverride() && position.getLastPrice() != null) {
            applyEurConversion(position);
            if (position.getPriceUpdatedAt() == null) {
                position.setPriceUpdatedAt(Instant.now());
            }
        } else if (position.getLastPrice() == null && !position.isManualPriceOverride()) {
            tryFetchQuote(position);
        } else if (position.getLastPrice() != null && position.getLastPriceEur() == null) {
            applyEurConversion(position);
        }

        PortfolioPosition saved = positionRepository.save(position);
        synchronizeDepotAndMonthlySnapshot(depot);
        return saved;
    }

    @Transactional
    public void delete(UUID positionId) {
        if (positionId == null) {
            return;
        }
        PortfolioPosition position = positionRepository.findById(positionId)
                .orElseThrow(() -> new IllegalArgumentException("Position not found: " + positionId));
        Account depot = requireInvestmentAccount(position.getAccountId());
        positionRepository.delete(position);
        synchronizeDepotAfterChange(depot);
    }

    @Transactional
    public int refreshAccountPrices(UUID accountId) {
        Account depot = requireInvestmentAccount(accountId);
        List<PortfolioPosition> positions = positionRepository.findByAccountIdOrderByNameAsc(accountId);
        if (positions.isEmpty()) {
            return 0;
        }
        int updatedCount = refreshPositions(positions);
        if (updatedCount > 0) {
            synchronizeDepotAndMonthlySnapshot(depot, positions);
        }
        return updatedCount;
    }

    @Transactional
    public int refreshAllPrices() {
        List<Account> depots = accountRepository.findByAccountTypeAndActiveTrue(AccountType.INVESTMENT);
        int totalUpdated = 0;
        for (Account depot : depots) {
            List<PortfolioPosition> positions = positionRepository.findByAccountIdOrderByNameAsc(depot.getId());
            if (positions.isEmpty()) {
                continue;
            }
            int updated = refreshPositions(positions);
            if (updated > 0) {
                synchronizeDepotAndMonthlySnapshot(depot, positions);
                totalUpdated += updated;
            }
        }
        return totalUpdated;
    }

    /**
     * Resolves a Yahoo-compatible ticker symbol from an ISIN when creating or
     * editing a holding in the UI.
     */
    public Optional<String> lookupTickerByIsin(String isin) {
        return marketPriceProvider.resolveTickerByIsin(isin);
    }

    @Transactional
    public void synchronizeDepot(UUID accountId) {
        Account depot = requireInvestmentAccount(accountId);
        synchronizeDepotAfterChange(depot);
    }

    private int refreshPositions(List<PortfolioPosition> positions) {
        int updatedCount = 0;
        for (PortfolioPosition position : positions) {
            if (position.isManualPriceOverride()) {
                if (position.getLastPrice() != null && !PortfolioPosition.DEFAULT_CURRENCY.equalsIgnoreCase(position.getCurrency())) {
                    applyEurConversion(position);
                    positionRepository.save(position);
                }
                continue;
            }
            if (tryFetchQuote(position)) {
                positionRepository.save(position);
                updatedCount++;
            }
        }
        return updatedCount;
    }

    private boolean tryFetchQuote(PortfolioPosition position) {
        String symbol = position.getTicker();
        if ((symbol == null || symbol.isBlank()) && position.getIsin() != null && !position.getIsin().isBlank()) {
            symbol = marketPriceProvider.resolveTickerByIsin(position.getIsin()).orElse(null);
            if (symbol != null) {
                position.setTicker(symbol);
            }
        }
        if (symbol == null || symbol.isBlank()) {
            return false;
        }
        Optional<MarketQuote> quoteOpt = marketPriceProvider.fetchQuote(symbol);
        if (quoteOpt.isEmpty()) {
            return false;
        }
        MarketQuote quote = quoteOpt.get();
        position.setLastPrice(quote.price().setScale(PRICE_SCALE, RoundingMode.HALF_UP));
        if (quote.currency() != null && !quote.currency().isBlank()) {
            position.setCurrency(quote.currency());
        }
        if (applyEurConversion(position)) {
            position.setPriceUpdatedAt(quote.fetchedAt() != null ? quote.fetchedAt() : Instant.now());
            return true;
        }
        return false;
    }

    private boolean applyEurConversion(PortfolioPosition position) {
        if (position.getLastPrice() == null) {
            return false;
        }
        String currency = position.getCurrency() == null ? PortfolioPosition.DEFAULT_CURRENCY : position.getCurrency();
        Optional<BigDecimal> fxRateOpt = resolveRateToEur(currency);
        if (fxRateOpt.isEmpty()) {
            LOG.warn("Missing FX rate for currency {} on position {}", currency, position.getName());
            return false;
        }
        BigDecimal fx = fxRateOpt.get();
        position.setFxRate(fx);
        position.setLastPriceEur(position.getLastPrice().multiply(fx).setScale(PRICE_SCALE, RoundingMode.HALF_UP));
        return true;
    }

    private Optional<BigDecimal> resolveRateToEur(String currency) {
        Optional<BigDecimal> ecbRate = exchangeRateService.getRateToEur(currency);
        if (ecbRate.isPresent()) {
            return ecbRate;
        }
        if (currency == null || currency.isBlank()
                || PortfolioPosition.DEFAULT_CURRENCY.equalsIgnoreCase(currency.trim())) {
            return Optional.of(BigDecimal.ONE);
        }
        return marketPriceProvider.fetchQuote(currency.trim().toUpperCase() + YAHOO_FX_SUFFIX)
                .map(MarketQuote::price)
                .filter(rate -> rate != null && rate.signum() > 0);
    }

    private void synchronizeDepotAfterChange(Account depot) {
        List<PortfolioPosition> remaining = positionRepository.findByAccountIdOrderByNameAsc(depot.getId());
        if (remaining.isEmpty()) {
            // Mode B fallback: restore latest manual snapshot balance
            BigDecimal latest = snapshotRepository.findFirstByAccountIdOrderBySnapshotDateDesc(depot.getId())
                    .map(AccountSnapshot::getBalance)
                    .orElse(depot.getInitialBalance());
            depot.setCurrentBalance(latest == null ? BigDecimal.ZERO : latest);
            accountRepository.save(depot);
            return;
        }
        synchronizeDepotAndMonthlySnapshot(depot, remaining);
    }

    private void synchronizeDepotAndMonthlySnapshot(Account depot) {
        List<PortfolioPosition> positions = positionRepository.findByAccountIdOrderByNameAsc(depot.getId());
        if (!positions.isEmpty()) {
            synchronizeDepotAndMonthlySnapshot(depot, positions);
        }
    }

    private void synchronizeDepotAndMonthlySnapshot(Account depot, List<PortfolioPosition> positions) {
        DepotValuation valuation = buildPositionValuation(depot.getId(), positions);
        depot.setCurrentBalance(valuation.currentValue());
        accountRepository.save(depot);

        upsertMonthlySnapshot(depot.getId(), valuation);
    }

    /**
     * Upserts at most one snapshot for the current calendar month so that
     * {@code BalanceChartPanel} tracks live depot performance without flooding
     * the monthly snapshot matrix with 365 daily rows per year.
     */
    private void upsertMonthlySnapshot(UUID accountId, DepotValuation valuation) {
        LocalDate today = LocalDate.now();
        YearMonth currentMonth = YearMonth.from(today);
        AccountSnapshot snapshot = snapshotRepository.findFirstByAccountIdOrderBySnapshotDateDesc(accountId)
                .filter(existing -> YearMonth.from(existing.getSnapshotDate()).equals(currentMonth))
                .orElseGet(() -> {
                    AccountSnapshot created = new AccountSnapshot();
                    created.setAccountId(accountId);
                    created.setSnapshotDate(today);
                    return created;
                });
        snapshot.setBalance(valuation.currentValue());
        if (valuation.investedCapital() != null) {
            snapshot.setInvestedCapital(valuation.investedCapital());
        }
        snapshotRepository.save(snapshot);
    }

    private static DepotValuation buildPositionValuation(UUID accountId, List<PortfolioPosition> positions) {
        BigDecimal totalValue = BigDecimal.ZERO;
        BigDecimal investedCapital = BigDecimal.ZERO;
        BigDecimal valueWithCostBasis = BigDecimal.ZERO;
        boolean hasAnyCostBasis = false;
        boolean allHaveCostBasis = true;

        for (PortfolioPosition pos : positions) {
            Optional<BigDecimal> marketValueOpt = pos.getMarketValueEur();
            if (marketValueOpt.isEmpty()) {
                // Exclude unpriced holdings from cost-basis performance so missing quotes never show as a 100% loss
                if (pos.getQuantity() != null && pos.getQuantity().signum() > 0) {
                    allHaveCostBasis = false;
                }
                continue;
            }
            BigDecimal marketValue = marketValueOpt.get();
            totalValue = totalValue.add(marketValue);
            Optional<BigDecimal> costBasis = pos.getCostBasisEur();
            if (costBasis.isPresent()) {
                hasAnyCostBasis = true;
                investedCapital = investedCapital.add(costBasis.get());
                valueWithCostBasis = valueWithCostBasis.add(marketValue);
            } else if (pos.getQuantity() != null && pos.getQuantity().signum() > 0) {
                allHaveCostBasis = false;
            }
        }

        return new DepotValuation(
                accountId,
                true,
                totalValue.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                hasAnyCostBasis ? investedCapital.setScale(MONEY_SCALE, RoundingMode.HALF_UP) : null,
                hasAnyCostBasis ? valueWithCostBasis.setScale(MONEY_SCALE, RoundingMode.HALF_UP) : null,
                hasAnyCostBasis && allHaveCostBasis,
                positions.size());
    }

    private static DepotValuation buildSnapshotValuation(Account account, AccountSnapshot snapshot) {
        BigDecimal balance = account.getCurrentBalance() == null ? BigDecimal.ZERO : account.getCurrentBalance();
        BigDecimal invested = snapshot != null ? snapshot.getInvestedCapital() : null;
        return new DepotValuation(
                account.getId(),
                false,
                balance.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                invested,
                invested != null ? balance.setScale(MONEY_SCALE, RoundingMode.HALF_UP) : null,
                invested != null,
                0);
    }

    Account requireInvestmentAccount(UUID accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("Depot account is required");
        }
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));
        if (account.getAccountType() != AccountType.INVESTMENT) {
            throw new IllegalArgumentException("Positions are only supported for INVESTMENT accounts");
        }
        if (householdService != null) {
            UUID effective = householdService.resolveEffectiveHouseholdId(null);
            if (effective != null && !effective.equals(account.getHouseholdId())) {
                throw new IllegalArgumentException("Account does not belong to current household");
            }
        }
        return account;
    }

    private static void validatePosition(PortfolioPosition position) {
        if (position.getName() == null || position.getName().isBlank()) {
            throw new IllegalArgumentException("Security name is required");
        }
        if (position.getQuantity() == null || position.getQuantity().signum() < 0) {
            throw new IllegalArgumentException("Quantity must be zero or positive");
        }
        if (position.getBuyInPrice() != null && position.getBuyInPrice().signum() < 0) {
            throw new IllegalArgumentException("Buy-in price must not be negative");
        }
        if (position.getLastPrice() != null && position.getLastPrice().signum() < 0) {
            throw new IllegalArgumentException("Last price must not be negative");
        }
    }

    private static void normalizeFields(PortfolioPosition position) {
        position.setName(position.getName().trim());
        position.setIsin(trimUpperOrNull(position.getIsin()));
        position.setTicker(trimUpperOrNull(position.getTicker()));
        position.setCurrency(position.getCurrency());
        if (position.getNotes() != null) {
            String trimmed = position.getNotes().trim();
            position.setNotes(trimmed.isEmpty() ? null : trimmed);
        }
    }

    private static String trimUpperOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().toUpperCase();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
