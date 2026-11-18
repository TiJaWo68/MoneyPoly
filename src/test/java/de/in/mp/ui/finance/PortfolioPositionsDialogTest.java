package de.in.mp.ui.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.UI;

import de.in.mp.finance.Account;
import de.in.mp.finance.AccountSnapshotService;
import de.in.mp.finance.AccountType;
import de.in.mp.finance.DepotImportResult;
import de.in.mp.finance.DepotValuation;
import de.in.mp.finance.PortfolioPosition;
import de.in.mp.finance.PortfolioPositionImportService;
import de.in.mp.finance.PortfolioPositionService;

class PortfolioPositionsDialogTest {

    @BeforeEach
    void setUp() {
        UI.setCurrent(new UI());
    }

    @AfterEach
    void tearDown() {
        UI.setCurrent(null);
    }

    @Test
    void opensAndRendersModeAAndModeBSummaryWithoutException() {
        PortfolioPositionService positionService = mock(PortfolioPositionService.class);
        AccountSnapshotService snapshotService = mock(AccountSnapshotService.class);

        Account depot = new Account();
        depot.setId(UUID.randomUUID());
        depot.setName("Direktbroker Depot");
        depot.setAccountType(AccountType.INVESTMENT);

        PortfolioPosition pos = new PortfolioPosition();
        pos.setId(UUID.randomUUID());
        pos.setAccountId(depot.getId());
        pos.setName("MSCI World");
        pos.setTicker("EUNL.DE");
        pos.setQuantity(new BigDecimal("100"));
        pos.setBuyInPrice(new BigDecimal("80.00"));
        pos.setLastPrice(new BigDecimal("100.00"));
        pos.setLastPriceEur(new BigDecimal("100.00"));

        DepotValuation modeA = new DepotValuation(
                depot.getId(), true, new BigDecimal("10000.00"),
                new BigDecimal("8000.00"), new BigDecimal("10000.00"), true, 1);

        when(positionService.findByAccount(depot.getId())).thenReturn(List.of(pos));
        when(snapshotService.findLatest(depot.getId())).thenReturn(Optional.empty());
        when(positionService.resolveValuation(depot, null)).thenReturn(modeA);

        PortfolioPositionsDialog dialog = new PortfolioPositionsDialog(positionService, snapshotService, true);
        assertThatCode(() -> dialog.initForDepot(depot, () -> {
        })).doesNotThrowAnyException();
        assertThat(pos.getPerformance()).isPresent();
        assertThat(pos.getPerformance().get().gain()).isEqualByComparingTo("2000.00");
    }

    @Test
    void triggersTradeRepublicPdfImportAndNotifiesCaller() {
        PortfolioPositionService positionService = mock(PortfolioPositionService.class);
        AccountSnapshotService snapshotService = mock(AccountSnapshotService.class);
        PortfolioPositionImportService importService = mock(PortfolioPositionImportService.class);

        Account depot = new Account();
        depot.setId(UUID.randomUUID());
        depot.setName("Direktbroker Depot");
        depot.setAccountType(AccountType.INVESTMENT);

        DepotValuation valuation = new DepotValuation(
                depot.getId(), true, new BigDecimal("1000.00"),
                new BigDecimal("900.00"), new BigDecimal("1000.00"), true, 1);
        when(positionService.findByAccount(depot.getId())).thenReturn(List.of());
        when(snapshotService.findLatest(depot.getId())).thenReturn(Optional.empty());
        when(positionService.resolveValuation(depot, null)).thenReturn(valuation);

        byte[] samplePdf = new byte[] { 1, 2, 3 };
        when(importService.importDepotPdf(depot.getId(), samplePdf))
                .thenReturn(new DepotImportResult(1, 0, 0, 0, 1));

        AtomicBoolean changed = new AtomicBoolean(false);
        PortfolioPositionsDialog dialog = new PortfolioPositionsDialog(
                positionService, snapshotService, importService, true);
        dialog.initForDepot(depot, () -> changed.set(true));

        assertThat(dialog.importPdf(samplePdf)).isTrue();
        verify(importService).importDepotPdf(depot.getId(), samplePdf);
        assertThat(changed).isTrue();
    }
}
