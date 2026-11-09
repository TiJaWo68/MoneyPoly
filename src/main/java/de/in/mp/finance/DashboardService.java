package de.in.mp.finance;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.in.mp.loans.LoanContractService;

/**
 * Aggregates the household net worth across accounts, securities and private
 * loan receivables for the dashboard KPIs.
 */
@Service
public class DashboardService {

    private final AccountService accountService;
    private final LoanContractService loanContractService;

    public DashboardService(AccountService accountService, LoanContractService loanContractService) {
        this.accountService = accountService;
        this.loanContractService = loanContractService;
    }

    @Transactional(readOnly = true)
    public DashboardKpis calculateKpis() {
        BigDecimal liquidity = accountService.calculateLiquidity();
        BigDecimal investedAssets = accountService.calculateInvestedAssets();
        BigDecimal loanReceivables = loanContractService.calculateActiveOutstanding();
        BigDecimal ownLiabilities = accountService.calculateTotalLiabilities();

        BigDecimal netWorth = liquidity
                .add(investedAssets)
                .add(loanReceivables)
                .subtract(ownLiabilities);

        return new DashboardKpis(netWorth, liquidity, investedAssets, loanReceivables);
    }
}
