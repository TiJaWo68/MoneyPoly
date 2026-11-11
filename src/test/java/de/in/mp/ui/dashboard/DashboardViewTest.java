package de.in.mp.ui.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.data.event.SortEvent;
import com.vaadin.flow.data.provider.SortDirection;

import de.in.mp.admin.PersonService;
import de.in.mp.finance.AccountService;
import de.in.mp.finance.DashboardKpis;
import de.in.mp.finance.DashboardService;
import de.in.mp.loans.LoanContractService;

class DashboardViewTest {

    private DashboardView view;

    @BeforeEach
    void setUp() {
        DashboardService dashboardService = mock(DashboardService.class);
        AccountService accountService = mock(AccountService.class);
        LoanContractService loanContractService = mock(LoanContractService.class);
        PersonService personService = mock(PersonService.class);

        when(dashboardService.calculateKpis()).thenReturn(
                new DashboardKpis(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        when(accountService.findAll()).thenReturn(List.of());
        when(loanContractService.findAll()).thenReturn(List.of());
        when(loanContractService.calculateActiveOutstanding()).thenReturn(BigDecimal.ZERO);
        when(personService.findAll()).thenReturn(List.of());

        view = new DashboardView(dashboardService, accountService, loanContractService, personService);
    }

    @Test
    void allColumnsOfAllTablesAreSortable() {
        for (Grid<?> grid : view.allGrids()) {
            for (DashboardSortColumn sortColumn : DashboardSortColumn.values()) {
                assertThat(grid.getColumnByKey(sortColumn.key()).isSortable()).isTrue();
            }
        }
    }

    @Test
    void sortingOneTableByClientClickAppliesSameSortToAllTables() {
        Grid<?> liquidityGrid = view.allGrids().get(0);
        fireClientSort(liquidityGrid, DashboardSortColumn.AMOUNT, SortDirection.DESCENDING);

        for (Grid<?> grid : view.allGrids()) {
            if (grid == liquidityGrid) {
                continue;
            }
            assertThat(grid.getSortOrder()).singleElement().satisfies(order -> {
                assertThat(order.getSorted().getKey()).isEqualTo(DashboardSortColumn.AMOUNT.key());
                assertThat(order.getDirection()).isEqualTo(SortDirection.DESCENDING);
            });
        }
    }

    @Test
    void clearingSortOnOneTableClearsSortOnAllTables() {
        Grid<?> investmentsGrid = view.allGrids().get(1);
        fireClientSort(investmentsGrid, DashboardSortColumn.SHARE, SortDirection.ASCENDING);

        fireClientSortCleared(investmentsGrid);

        for (Grid<?> grid : view.allGrids()) {
            if (grid != investmentsGrid) {
                assertThat(grid.getSortOrder()).isEmpty();
            }
        }
    }

    private static <T> void fireClientSort(Grid<T> grid, DashboardSortColumn sortColumn, SortDirection direction) {
        GridSortOrder<T> order = new GridSortOrder<>(grid.getColumnByKey(sortColumn.key()), direction);
        ComponentUtil.fireEvent(grid, new SortEvent<Grid<T>, GridSortOrder<T>>(grid, List.of(order), true));
    }

    private static <T> void fireClientSortCleared(Grid<T> grid) {
        ComponentUtil.fireEvent(grid, new SortEvent<Grid<T>, GridSortOrder<T>>(grid, List.of(), true));
    }
}
