package de.in.mp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PortfolioPositionServiceTest {

    private PortfolioPositionRepository positionRepository;
    private AccountRepository accountRepository;
    private AccountSnapshotRepository snapshotRepository;
    private MarketPriceProvider marketPriceProvider;
    private EcbExchangeRateService exchangeRateService;
    private HouseholdService householdService;
    private PortfolioPositionService service;

    @BeforeEach
    void setUp() {
        positionRepository = mock(PortfolioPositionRepository.class);
        accountRepository = mock(AccountRepository.class);
        snapshotRepository = mock(AccountSnapshotRepository.class);
        marketPriceProvider = mock(MarketPriceProvider.class);
        exchangeRateService = mock(EcbExchangeRateService.class);
        householdService = mock(HouseholdService.class);

        when(householdService.resolveEffectiveHouseholdId(null)).thenReturn(null);
        when(exchangeRateService.getRateToEur("EUR")).thenReturn(Optional.of(BigDecimal.ONE));
        when(positionRepository.save(any())).thenAnswer(inv -> {
            PortfolioPosition p = inv.getArgument(0);
            if (p.getId() == null) {
                p.setId(UUID.randomUUID());
            }
            return p;
        });

        service = new PortfolioPositionService(
                positionRepository,
                accountRepository,
                snapshotRepository,
                marketPriceProvider,
                exchangeRateService,
                householdService);
    }

    @Test
    void modeAValuationTakesPrecedenceOverSnapshotWhenPositionsExist() {
        UUID depotId = UUID.randomUUID();
        Account depot = investmentAccount(depotId, new BigDecimal("5000.00"));

        PortfolioPosition pos1 = position(depotId, "MSCI World", "EUNL.DE", "EUR",
                new BigDecimal("100"), new BigDecimal("80.00"), new BigDecimal("100.00"));
        PortfolioPosition pos2 = position(depotId, "S&P 500", "CSPX.L", "USD",
                new BigDecimal("10"), new BigDecimal("400.00"), new BigDecimal("500.00"));
        pos2.setFxRate(new BigDecimal("0.900000"));
        pos2.setLastPriceEur(new BigDecimal("450.0000")); // 10 * 450 = 4500

        AccountSnapshot olderSnapshot = new AccountSnapshot();
        olderSnapshot.setAccountId(depotId);
        olderSnapshot.setBalance(new BigDecimal("5000.00"));
        olderSnapshot.setInvestedCapital(new BigDecimal("4000.00"));

        when(positionRepository.findByAccountIdIn(List.of(depotId))).thenReturn(List.of(pos1, pos2));

        Map<UUID, DepotValuation> valuations = service.resolveValuations(
                List.of(depot), Map.of(depotId, olderSnapshot));

        DepotValuation val = valuations.get(depotId);
        assertThat(val.positionBased()).isTrue();
        assertThat(val.positionCount()).isEqualTo(2);
        // 100 * 100 + 10 * 450 = 14500.00
        assertThat(val.currentValue()).isEqualByComparingTo("14500.00");
        // 100 * 80 + 10 * 400 = 12000.00
        assertThat(val.investedCapital()).isEqualByComparingTo("12000.00");
        assertThat(val.costBasisComplete()).isTrue();
        assertThat(val.performance()).isPresent();
        assertThat(val.performance().get().gain()).isEqualByComparingTo("2500.00");
    }

    @Test
    void modeBFallsBackToSnapshotWhenNoPositionsExist() {
        UUID depotId = UUID.randomUUID();
        Account depot = investmentAccount(depotId, new BigDecimal("52210.00"));

        AccountSnapshot snap = new AccountSnapshot();
        snap.setAccountId(depotId);
        snap.setBalance(new BigDecimal("52210.00"));
        snap.setInvestedCapital(new BigDecimal("48000.00"));

        when(positionRepository.findByAccountIdIn(List.of(depotId))).thenReturn(List.of());

        Map<UUID, DepotValuation> valuations = service.resolveValuations(List.of(depot), Map.of(depotId, snap));
        DepotValuation val = valuations.get(depotId);

        assertThat(val.positionBased()).isFalse();
        assertThat(val.currentValue()).isEqualByComparingTo("52210.00");
        assertThat(val.investedCapital()).isEqualByComparingTo("48000.00");
        assertThat(val.performance()).isPresent();
        assertThat(val.performance().get().gain()).isEqualByComparingTo("4210.00");
    }

    @Test
    void refreshesUsdQuoteConvertsViaEcbAndUpdatesMonthlySnapshotInPlace() {
        UUID depotId = UUID.randomUUID();
        Account depot = investmentAccount(depotId, BigDecimal.ZERO);

        PortfolioPosition apple = position(depotId, "Apple", "AAPL", "USD",
                new BigDecimal("10"), new BigDecimal("150.00"), new BigDecimal("180.00"));
        apple.setLastPriceEur(new BigDecimal("162.00"));

        AccountSnapshot currentMonthSnap = new AccountSnapshot();
        currentMonthSnap.setId(UUID.randomUUID());
        currentMonthSnap.setAccountId(depotId);
        currentMonthSnap.setSnapshotDate(LocalDate.now().withDayOfMonth(1));
        currentMonthSnap.setBalance(new BigDecimal("1620.00"));

        when(accountRepository.findById(depotId)).thenReturn(Optional.of(depot));
        when(positionRepository.findByAccountIdOrderByNameAsc(depotId)).thenReturn(List.of(apple));
        when(marketPriceProvider.fetchQuote("AAPL"))
                .thenReturn(Optional.of(new MarketQuote("AAPL", new BigDecimal("200.00"), "USD", Instant.now())));
        when(exchangeRateService.getRateToEur("USD")).thenReturn(Optional.of(new BigDecimal("0.900000")));
        when(snapshotRepository.findFirstByAccountIdOrderBySnapshotDateDesc(depotId))
                .thenReturn(Optional.of(currentMonthSnap));

        int updated = service.refreshAccountPrices(depotId);

        assertThat(updated).isEqualTo(1);
        assertThat(apple.getLastPrice()).isEqualByComparingTo("200.00");
        assertThat(apple.getLastPriceEur()).isEqualByComparingTo("180.0000");
        // Depot balance synced to 10 * 180 = 1800.00
        assertThat(depot.getCurrentBalance()).isEqualByComparingTo("1800.00");

        ArgumentCaptor<AccountSnapshot> snapCaptor = ArgumentCaptor.forClass(AccountSnapshot.class);
        verify(snapshotRepository).save(snapCaptor.capture());
        assertThat(snapCaptor.getValue().getId()).isEqualTo(currentMonthSnap.getId());
        assertThat(snapCaptor.getValue().getBalance()).isEqualByComparingTo("1800.00");
        assertThat(snapCaptor.getValue().getInvestedCapital()).isEqualByComparingTo("1500.00");
    }

    @Test
    void skipsManualOverridePositionsDuringApiRefreshAndKeepsLastPriceOnFailure() {
        UUID depotId = UUID.randomUUID();
        Account depot = investmentAccount(depotId, new BigDecimal("1000.00"));

        PortfolioPosition manual = position(depotId, "Private Fond", null, "EUR",
                new BigDecimal("10"), new BigDecimal("90.00"), new BigDecimal("100.00"));
        manual.setManualPriceOverride(true);
        manual.setLastPriceEur(new BigDecimal("100.0000"));

        PortfolioPosition failing = position(depotId, "Broken Ticker", "XXXX.DE", "EUR",
                new BigDecimal("5"), new BigDecimal("50.00"), new BigDecimal("60.00"));
        failing.setLastPriceEur(new BigDecimal("60.0000"));

        when(accountRepository.findById(depotId)).thenReturn(Optional.of(depot));
        when(positionRepository.findByAccountIdOrderByNameAsc(depotId)).thenReturn(List.of(manual, failing));
        when(marketPriceProvider.fetchQuote("XXXX.DE")).thenReturn(Optional.empty());
        when(snapshotRepository.findFirstByAccountIdOrderBySnapshotDateDesc(depotId)).thenReturn(Optional.empty());

        int updated = service.refreshAccountPrices(depotId);

        assertThat(updated).isZero();
        assertThat(manual.getLastPriceEur()).isEqualByComparingTo("100.0000");
        assertThat(failing.getLastPriceEur()).isEqualByComparingTo("60.0000");
        verify(marketPriceProvider, never()).fetchQuote(null);
    }

    @Test
    void rejectsNonInvestmentAccount() {
        UUID checkingId = UUID.randomUUID();
        Account checking = new Account();
        checking.setId(checkingId);
        checking.setAccountType(AccountType.CHECKING);
        when(accountRepository.findById(checkingId)).thenReturn(Optional.of(checking));

        PortfolioPosition pos = new PortfolioPosition();
        pos.setAccountId(checkingId);
        pos.setName("Test");
        pos.setQuantity(BigDecimal.ONE);

        assertThatThrownBy(() -> service.save(pos))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVESTMENT");
    }

    @Test
    void deletingLastPositionRevertsDepotBalanceToLatestSnapshot() {
        UUID depotId = UUID.randomUUID();
        Account depot = investmentAccount(depotId, new BigDecimal("14500.00"));
        UUID posId = UUID.randomUUID();
        PortfolioPosition pos = position(depotId, "EUNL", "EUNL.DE", "EUR",
                new BigDecimal("10"), new BigDecimal("80.00"), new BigDecimal("100.00"));
        pos.setId(posId);

        AccountSnapshot snap = new AccountSnapshot();
        snap.setAccountId(depotId);
        snap.setBalance(new BigDecimal("5000.00"));

        when(positionRepository.findById(posId)).thenReturn(Optional.of(pos));
        when(accountRepository.findById(depotId)).thenReturn(Optional.of(depot));
        when(positionRepository.findByAccountIdOrderByNameAsc(depotId)).thenReturn(List.of());
        when(snapshotRepository.findFirstByAccountIdOrderBySnapshotDateDesc(depotId))
                .thenReturn(Optional.of(snap));

        service.delete(posId);

        assertThat(depot.getCurrentBalance()).isEqualByComparingTo("5000.00");
        verify(accountRepository).save(depot);
    }

    @Test
    void unpricedPositionDoesNotCountAsTotalLossInPerformance() {
        UUID depotId = UUID.randomUUID();
        Account depot = investmentAccount(depotId, BigDecimal.ZERO);

        PortfolioPosition priced = position(depotId, "Core Stoxx", "MEUD.PA", "EUR",
                new BigDecimal("100"), new BigDecimal("300.00"), new BigDecimal("330.00"));
        PortfolioPosition unpriced = position(depotId, "Novo Nordisk", "NVO", "USD",
                new BigDecimal("126.2159"), new BigDecimal("77.29"), null);

        when(positionRepository.findByAccountIdIn(List.of(depotId))).thenReturn(List.of(priced, unpriced));

        DepotValuation val = service.resolveValuation(depot, null);

        assertThat(val.currentValue()).isEqualByComparingTo("33000.00");
        assertThat(val.costBasisComplete()).isFalse();
        assertThat(val.performance()).isPresent();
        assertThat(val.performance().get().gain()).isEqualByComparingTo("3000.00");
    }

    @Test
    void fallsBackToYahooFxQuoteWhenEcbRateUnavailable() {
        UUID depotId = UUID.randomUUID();
        Account depot = investmentAccount(depotId, BigDecimal.ZERO);

        PortfolioPosition novo = new PortfolioPosition();
        novo.setId(UUID.randomUUID());
        novo.setAccountId(depotId);
        novo.setName("Novo Nordisk");
        novo.setTicker("NVO");
        novo.setCurrency("EUR");
        novo.setQuantity(new BigDecimal("10"));
        novo.setBuyInPrice(new BigDecimal("77.29"));

        when(accountRepository.findById(depotId)).thenReturn(Optional.of(depot));
        when(positionRepository.findByAccountIdOrderByNameAsc(depotId)).thenReturn(List.of(novo));
        when(marketPriceProvider.fetchQuote("NVO"))
                .thenReturn(Optional.of(new MarketQuote("NVO", new BigDecimal("70.00"), "USD", Instant.now())));
        when(exchangeRateService.getRateToEur("USD")).thenReturn(Optional.empty());
        when(marketPriceProvider.fetchQuote("USDEUR=X"))
                .thenReturn(Optional.of(new MarketQuote("USDEUR=X", new BigDecimal("0.9000"), "EUR", Instant.now())));
        when(snapshotRepository.findFirstByAccountIdOrderBySnapshotDateDesc(depotId)).thenReturn(Optional.empty());

        int updated = service.refreshAccountPrices(depotId);

        assertThat(updated).isEqualTo(1);
        assertThat(novo.getLastPrice()).isEqualByComparingTo("70.0000");
        assertThat(novo.getLastPriceEur()).isEqualByComparingTo("63.0000");
        assertThat(depot.getCurrentBalance()).isEqualByComparingTo("630.00");
    }

    private static Account investmentAccount(UUID id, BigDecimal balance) {
        Account account = new Account();
        account.setId(id);
        account.setName("Depot");
        account.setAccountType(AccountType.INVESTMENT);
        account.setCurrentBalance(balance);
        account.setInitialBalance(BigDecimal.ZERO);
        return account;
    }

    private static PortfolioPosition position(UUID accountId, String name, String ticker, String currency,
            BigDecimal qty, BigDecimal buyIn, BigDecimal lastPrice) {
        PortfolioPosition p = new PortfolioPosition();
        p.setId(UUID.randomUUID());
        p.setAccountId(accountId);
        p.setName(name);
        p.setTicker(ticker);
        p.setCurrency(currency);
        p.setQuantity(qty);
        p.setBuyInPrice(buyIn);
        p.setLastPrice(lastPrice);
        if ("EUR".equals(currency)) {
            p.setLastPriceEur(lastPrice);
            p.setFxRate(BigDecimal.ONE);
        }
        return p;
    }
}
