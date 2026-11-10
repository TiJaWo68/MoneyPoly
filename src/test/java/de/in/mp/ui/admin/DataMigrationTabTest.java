package de.in.mp.ui.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.UI;

import de.in.mp.admin.DatabaseResetService;
import de.in.mp.energy.EnergyOdsImportService;
import de.in.mp.finance.AccountType;
import de.in.mp.finance.SnapshotValue;
import de.in.mp.importer.FinanceOdsImportService;
import de.in.mp.importer.OdsAccountColumn;

class DataMigrationTabTest {

    @BeforeEach
    void setUp() {
        UI.setCurrent(new UI());
    }

    @AfterEach
    void tearDown() {
        UI.setCurrent(null);
    }

    @Test
    void financeMigrationDialogAllowsSelectingAndDeselectingAllColumns() {
        FinanceOdsImportService financeImportService = mock(FinanceOdsImportService.class);
        OdsAccountColumn col1 = sampleColumn("C24", "Giro");
        OdsAccountColumn col2 = sampleColumn("S+O", "Auto");
        when(financeImportService.analyze(any())).thenReturn(List.of(col1, col2));

        FinanceOdsMigrationDialog dialog = new FinanceOdsMigrationDialog(financeImportService);
        dialog.setColumnsForTest(List.of(col1, col2));

        dialog.setAllIncluded(false);
        assertThat(col1.isInclude()).isFalse();
        assertThat(col2.isInclude()).isFalse();

        dialog.setAllIncluded(true);
        assertThat(col1.isInclude()).isTrue();
        assertThat(col2.isInclude()).isTrue();
    }

    @Test
    void databaseResetExecutesOnlyWhenConfirmed() {
        FinanceOdsImportService financeImportService = mock(FinanceOdsImportService.class);
        EnergyOdsImportService energyImportService = mock(EnergyOdsImportService.class);
        DatabaseResetService databaseResetService = mock(DatabaseResetService.class);

        DataMigrationTab tab = new DataMigrationTab(financeImportService, energyImportService, databaseResetService);
        verify(databaseResetService, never()).resetAllDomainData();

        tab.executeDatabaseReset();
        verify(databaseResetService).resetAllDomainData();
    }

    private static OdsAccountColumn sampleColumn(String institution, String subHeader) {
        OdsAccountColumn column = new OdsAccountColumn(
                "Balances",
                1,
                institution,
                subHeader,
                List.of(SnapshotValue.ofBalance(LocalDate.of(2026, 10, 31), BigDecimal.TEN)));
        column.setInstitutionName(institution);
        column.setAccountName(subHeader);
        column.setAccountType(AccountType.INVESTMENT);
        column.setInclude(true);
        return column;
    }
}

