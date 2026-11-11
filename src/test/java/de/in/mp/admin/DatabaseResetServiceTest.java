package de.in.mp.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class DatabaseResetServiceTest {

    @Test
    void resetAllDomainDataClearsAllDomainTablesAndPreservesLocalAdminAndBankDirectory() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DatabaseResetService service = new DatabaseResetService(jdbcTemplate);

        service.resetAllDomainData();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(18)).update(sqlCaptor.capture());

        List<String> statements = sqlCaptor.getAllValues();
        assertThat(statements).containsExactly(
                DatabaseResetService.SQL_UNLINK_USER_PERSONS,
                DatabaseResetService.SQL_DELETE_NON_LOCAL_ADMIN_USERS,
                "DELETE FROM transaction_allocation",
                "DELETE FROM loan_repayment",
                "DELETE FROM loan_contract_person",
                "DELETE FROM ev_charging_reimbursement",
                "DELETE FROM classification_rule",
                "DELETE FROM bank_transaction",
                "DELETE FROM account_snapshot",
                "UPDATE finance_account SET parent_id = NULL",
                "DELETE FROM finance_account",
                "DELETE FROM loan_contract",
                "DELETE FROM category",
                "DELETE FROM meter_reading",
                "DELETE FROM meter",
                "DELETE FROM energy_tariff",
                "DELETE FROM energy_asset",
                "DELETE FROM person");

        assertThat(statements).noneMatch(sql -> sql.contains("bank_directory"));
        assertThat(statements).noneMatch(sql -> sql.contains("flyway_schema_history"));
    }

    @Test
    void resetFinanceDataClearsOnlyFinanceTablesAndPreservesPersonsLoansAndEnergy() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DatabaseResetService service = new DatabaseResetService(jdbcTemplate);

        service.resetFinanceData();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(5)).update(sqlCaptor.capture());

        List<String> statements = sqlCaptor.getAllValues();
        assertThat(statements).containsExactly(
                "DELETE FROM transaction_allocation",
                "DELETE FROM bank_transaction",
                "DELETE FROM account_snapshot",
                "UPDATE finance_account SET parent_id = NULL",
                "DELETE FROM finance_account");
        assertThat(statements).noneMatch(sql -> sql.contains("person") || sql.contains("loan_contract")
                || sql.contains("meter"));
    }

    @Test
    void resetEnergyDataClearsOnlyEnergyTablesAndPreservesFinancePersonsAndLoans() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DatabaseResetService service = new DatabaseResetService(jdbcTemplate);

        service.resetEnergyData();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(5)).update(sqlCaptor.capture());

        List<String> statements = sqlCaptor.getAllValues();
        assertThat(statements).containsExactly(
                "DELETE FROM ev_charging_reimbursement",
                "DELETE FROM meter_reading",
                "DELETE FROM meter",
                "DELETE FROM energy_tariff",
                "DELETE FROM energy_asset");
        assertThat(statements).noneMatch(sql -> sql.contains("finance_account") || sql.contains("person")
                || sql.contains("loan_contract"));
    }
}

