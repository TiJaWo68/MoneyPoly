package de.in.mp.finance;

import java.math.BigDecimal;
import java.util.UUID;

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
                return calculateKpis(null);
        }

        @Transactional(readOnly = true)
        public DashboardKpis calculateKpis(UUID householdId) {
                BigDecimal liquidity = householdId == null
                                ? accountService.calculateLiquidity()
                                : accountService.calculateLiquidity(householdId);
                BigDecimal investedAssets = householdId == null
                                ? accountService.calculateInvestedAssets()
                                : accountService.calculateInvestedAssets(householdId);
                BigDecimal loanReceivables = householdId == null
                                ? loanContractService.calculateActiveOutstanding()
                                : loanContractService.calculateActiveOutstanding(householdId);
                BigDecimal ownLiabilities = householdId == null
                                ? accountService.calculateTotalLiabilities()
                                : accountService.calculateTotalLiabilities(householdId);

                BigDecimal netWorth = liquidity
                                .add(investedAssets)
                                .add(loanReceivables)
                                .subtract(ownLiabilities);

                return new DashboardKpis(netWorth, liquidity, investedAssets, loanReceivables);
        }
}
