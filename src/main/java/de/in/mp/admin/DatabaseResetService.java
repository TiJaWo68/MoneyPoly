package de.in.mp.admin;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resets all domain and master data in the database for repeatable testing
 * and re-migration while preserving local administrator accounts and the
 * static bank directory (V6).
 */
@Service
public class DatabaseResetService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseResetService.class);

    static final String SQL_UNLINK_USER_PERSONS = "UPDATE app_user SET person_id = NULL";

    static final String SQL_DELETE_NON_LOCAL_ADMIN_USERS = "DELETE FROM app_user "
            + "WHERE auth_provider <> 'LOCAL' "
            + "OR id NOT IN (SELECT app_user_id FROM app_user_role WHERE role = 'ROLE_ADMIN')";

    static final List<String> FINANCE_CLEANUP_STATEMENTS = List.of(
            "DELETE FROM transaction_allocation",
            "DELETE FROM bank_transaction",
            "DELETE FROM account_snapshot",
            "UPDATE finance_account SET parent_id = NULL",
            "DELETE FROM finance_account");

    static final List<String> ENERGY_CLEANUP_STATEMENTS = List.of(
            "DELETE FROM ev_charging_reimbursement",
            "DELETE FROM meter_reading",
            "DELETE FROM meter",
            "DELETE FROM energy_tariff",
            "DELETE FROM energy_asset");

    private static final List<String> DOMAIN_CLEANUP_STATEMENTS = List.of(
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

    private final JdbcTemplate jdbcTemplate;

    public DatabaseResetService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Deletes all finance account, snapshot and transaction data before a
     * finance ODS import while keeping persons, private loans, energy data and
     * users intact.
     */
    @Transactional
    public void resetFinanceData() {
        LOGGER.info("Resetting finance tables for ODS import");
        for (String statement : FINANCE_CLEANUP_STATEMENTS) {
            jdbcTemplate.update(statement);
        }
    }

    /**
     * Deletes all energy asset, tariff, meter and meter reading data before an
     * energy ODS import while keeping finance, loan, person and user data
     * intact.
     */
    @Transactional
    public void resetEnergyData() {
        LOGGER.info("Resetting energy tables for ODS import");
        for (String statement : ENERGY_CLEANUP_STATEMENTS) {
            jdbcTemplate.update(statement);
        }
    }

    /**
     * Deletes all financial, loan, energy, category and person data while
     * keeping local {@code ROLE_ADMIN} user accounts and the {@code bank_directory}
     * lookup table intact.
     */
    @Transactional
    public void resetAllDomainData() {
        LOGGER.info("Resetting all domain data (preserving local admin users and bank directory)");
        jdbcTemplate.update(SQL_UNLINK_USER_PERSONS);
        jdbcTemplate.update(SQL_DELETE_NON_LOCAL_ADMIN_USERS);
        for (String statement : DOMAIN_CLEANUP_STATEMENTS) {
            jdbcTemplate.update(statement);
        }
        LOGGER.info("Database domain data reset completed successfully");
    }
}

