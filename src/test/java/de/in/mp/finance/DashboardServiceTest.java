package de.in.mp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.in.mp.loans.LoanContractService;

class DashboardServiceTest {

    private AccountService accountService;
    private LoanContractService loanContractService;
    private DashboardService service;

    @BeforeEach
    void setUp() {
        accountService = mock(AccountService.class);
        loanContractService = mock(LoanContractService.class);
        service = new DashboardService(accountService, loanContractService);
    }

    @Test
    void calculatesNetWorthFromLiquidityInvestedReceivablesAndLiabilities() {
        when(accountService.calculateLiquidity()).thenReturn(new BigDecimal("5000.00"));
        when(accountService.calculateInvestedAssets()).thenReturn(new BigDecimal("25000.00"));
        when(accountService.calculateTotalLiabilities()).thenReturn(new BigDecimal("8000.00"));
        when(loanContractService.calculateActiveOutstanding()).thenReturn(new BigDecimal("12000.00"));

        DashboardKpis kpis = service.calculateKpis();

        assertThat(kpis.liquidity()).isEqualByComparingTo("5000.00");
        assertThat(kpis.investedAssets()).isEqualByComparingTo("25000.00");
        assertThat(kpis.loanReceivables()).isEqualByComparingTo("12000.00");
        assertThat(kpis.netWorth()).isEqualByComparingTo("34000.00");
    }
}
