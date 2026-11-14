package de.in.mp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import de.in.mp.importer.ImportedDepotPosition;
import de.in.mp.importer.PdfTextExtractor;
import de.in.mp.importer.TradeRepublicStatementParser;

class PortfolioPositionImportServiceTest {

    private PortfolioPositionRepository positionRepository;
    private PortfolioPositionService positionService;
    private MarketPriceProvider marketPriceProvider;
    private PdfTextExtractor pdfTextExtractor;
    private TradeRepublicStatementParser statementParser;
    private PortfolioPositionImportService importService;

    @BeforeEach
    void setUp() {
        positionRepository = mock(PortfolioPositionRepository.class);
        positionService = mock(PortfolioPositionService.class);
        marketPriceProvider = mock(MarketPriceProvider.class);
        pdfTextExtractor = mock(PdfTextExtractor.class);
        statementParser = mock(TradeRepublicStatementParser.class);

        when(positionRepository.save(any())).thenAnswer(inv -> {
            PortfolioPosition p = inv.getArgument(0);
            if (p.getId() == null) {
                p.setId(UUID.randomUUID());
            }
            return p;
        });

        importService = new PortfolioPositionImportService(
                positionRepository,
                positionService,
                marketPriceProvider,
                pdfTextExtractor,
                statementParser);
    }

    @Test
    @SuppressWarnings("unchecked")
    void consolidatesMultiTranchesPreservesBuyInsAddsNewAndDeletesRemoved() {
        UUID depotId = UUID.randomUUID();
        Account depot = investmentAccount(depotId);

        PortfolioPosition etfTranche1 = pos(depotId, "Core Stoxx 600", "XY0000000003", "MEUD.PA",
                "315", "307.95");
        PortfolioPosition etfTranche2 = pos(depotId, "Core Stoxx 600", "XY0000000003", "MEUD.PA",
                "0.0885", "307.95");
        PortfolioPosition etfTranche3 = pos(depotId, "Core Stoxx 600", "XY0000000003", "MEUD.PA",
                "16.3292", "306.26");
        PortfolioPosition alphaExisting = pos(depotId, "Alpha Corp", "XY0000000002", "NVO",
                "126.2159", "77.29");
        alphaExisting.setCurrency("USD");
        alphaExisting.setLastPrice(null);
        alphaExisting.setLastPriceEur(null);

        PortfolioPosition soldPosition = pos(depotId, "Sold AG", "XY9999999999", "SOLD.DE",
                "10", "50.00");

        when(positionService.requireInvestmentAccount(depotId)).thenReturn(depot);
        when(positionRepository.findByAccountIdOrderByNameAsc(depotId)).thenReturn(
                List.of(etfTranche1, etfTranche2, etfTranche3, alphaExisting, soldPosition));

        byte[] pdfBytes = new byte[] { 1, 2, 3 };
        when(pdfTextExtractor.extract(pdfBytes)).thenReturn("TR PDF");
        when(statementParser.supports("TR PDF")).thenReturn(true);
        when(statementParser.parse("TR PDF")).thenReturn(List.of(
                new ImportedDepotPosition("Core Stoxx Example 600 EUR (Acc)", "XY0000000003",
                        new BigDecimal("331.417713"), new BigDecimal("328.30"), new BigDecimal("108804.44")),
                new ImportedDepotPosition("Alpha Corp ADR", "XY0000000002",
                        new BigDecimal("126.215889"), new BigDecimal("37.44"), new BigDecimal("4725.52")),
                new ImportedDepotPosition("Example Gold USD (Acc)", "XY0000000004",
                        new BigDecimal("37.207772"), new BigDecimal("68.15"), new BigDecimal("2535.52"))));
        when(marketPriceProvider.resolveTickerByIsin("XY0000000004")).thenReturn(Optional.of("EGLN.DE"));

        DepotImportResult result = importService.importTradeRepublicPdf(depotId, pdfBytes);

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.updated()).isEqualTo(2);
        assertThat(result.consolidated()).isEqualTo(2);
        assertThat(result.deleted()).isEqualTo(1);
        assertThat(result.totalImported()).isEqualTo(3);

        // Consolidated ETF: weighted avg buy-in = (315*307.95 + 0.0885*307.95 + 16.3292*306.26) / 331.4177 = 307.8667
        assertThat(etfTranche1.getQuantity()).isEqualByComparingTo("331.417713");
        assertThat(etfTranche1.getBuyInPrice()).isEqualByComparingTo("307.8667");
        assertThat(etfTranche1.getTicker()).isEqualTo("MEUD.PA");
        assertThat(etfTranche1.getLastPriceEur()).isEqualByComparingTo("328.30");

        // Updated single position: keeps buy-in & ticker, takes 6-decimal quantity and PDF start price
        assertThat(alphaExisting.getQuantity()).isEqualByComparingTo("126.215889");
        assertThat(alphaExisting.getBuyInPrice()).isEqualByComparingTo("77.29");
        assertThat(alphaExisting.getTicker()).isEqualTo("NVO");
        assertThat(alphaExisting.getLastPriceEur()).isEqualByComparingTo("37.44");

        // Deleted: 2 duplicate ETF tranches + 1 sold position = 3 deletions
        ArgumentCaptor<Iterable<PortfolioPosition>> deletedCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(positionRepository).deleteAll(deletedCaptor.capture());
        List<PortfolioPosition> deletedList = new ArrayList<>();
        deletedCaptor.getValue().forEach(deletedList::add);
        assertThat(deletedList).containsExactlyInAnyOrder(etfTranche2, etfTranche3, soldPosition);

        verify(positionService).synchronizeDepot(depotId);
    }

    @Test
    void rejectsUnsupportedPdfFormat() {
        UUID depotId = UUID.randomUUID();
        when(positionService.requireInvestmentAccount(depotId)).thenReturn(investmentAccount(depotId));
        byte[] pdfBytes = new byte[] { 1 };
        when(pdfTextExtractor.extract(pdfBytes)).thenReturn("Random PDF");
        when(statementParser.supports("Random PDF")).thenReturn(false);

        assertThatThrownBy(() -> importService.importTradeRepublicPdf(depotId, pdfBytes))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Account investmentAccount(UUID id) {
        Account a = new Account();
        a.setId(id);
        a.setName("Brokerage");
        a.setAccountType(AccountType.INVESTMENT);
        a.setCurrentBalance(BigDecimal.ZERO);
        return a;
    }

    private static PortfolioPosition pos(UUID accountId, String name, String isin, String ticker,
            String qty, String buyIn) {
        PortfolioPosition p = new PortfolioPosition();
        p.setId(UUID.randomUUID());
        p.setAccountId(accountId);
        p.setName(name);
        p.setIsin(isin);
        p.setTicker(ticker);
        p.setQuantity(new BigDecimal(qty));
        p.setBuyInPrice(new BigDecimal(buyIn));
        p.setCurrency("EUR");
        return p;
    }
}
