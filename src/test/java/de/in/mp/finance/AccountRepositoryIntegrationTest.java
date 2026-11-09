package de.in.mp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Persistence integration test that runs all Flyway migrations against a real
 * PostgreSQL instance and verifies the {@link Account} mapping and the
 * {@link AccountRepository} queries. Tagged {@code integration} so it is skipped
 * during a local {@code mvn test} without Docker and executed via the
 * {@code integration-tests} Maven profile.
 */
@SpringBootTest
@Testcontainers
@Tag("integration")
class AccountRepositoryIntegrationTest {

    private static final String POSTGRES_IMAGE = "postgres:16-alpine";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE);

    @DynamicPropertySource
    static void registerDatasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migrationCreatesFinanceAccountTable() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = 'finance_account'",
                Integer.class);

        assertThat(count).isEqualTo(1);
    }

    @Test
    void checkConstraintRejectsInvalidAccountType() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO finance_account (name, account_type) VALUES (?, ?)", "Invalid", "CASH"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void persistsAccountWithStringMappedType() {
        Account account = account("Targobank", AccountType.CHECKING, new BigDecimal("1234.56"));

        Account saved = accountRepository.save(account);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getAccountType()).isEqualTo(AccountType.CHECKING);

        Account reloaded = accountRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getAccountType()).isEqualTo(AccountType.CHECKING);
        assertThat(reloaded.getCurrentBalance()).isEqualByComparingTo("1234.56");
    }

    @Test
    void activeQuerySortsByTypeAndNameAndExcludesDeactivated() {
        accountRepository.save(account("Targobank", AccountType.CHECKING, new BigDecimal("500.00")));
        accountRepository.save(account("C24", AccountType.CHECKING, new BigDecimal("200.00")));
        accountRepository.save(account("VW Bank", AccountType.INVESTMENT, new BigDecimal("1000.00")));
        accountRepository.save(account("Autokredit", AccountType.LOAN, new BigDecimal("800.00")));

        Account inactive = account("Sparkasse", AccountType.SAVINGS, new BigDecimal("10.00"));
        inactive.setActive(false);
        accountRepository.save(inactive);

        List<Account> active = accountRepository.findByActiveTrueOrderByAccountTypeAscNameAsc();

        assertThat(active).hasSize(4);
        assertThat(active).extracting(Account::getName)
                .containsExactly("C24", "Targobank", "VW Bank", "Autokredit");
    }

    @Test
    void persistsInstitutionAccountWithParentLink() {
        Account institution = account("Trade Republic", AccountType.INSTITUTION, BigDecimal.ZERO);
        Account savedInstitution = accountRepository.save(institution);

        Account cash = account("Verrechnungskonto", AccountType.CHECKING, new BigDecimal("100.00"));
        cash.setParentId(savedInstitution.getId());
        Account savedCash = accountRepository.save(cash);

        Account reloaded = accountRepository.findById(savedCash.getId()).orElseThrow();
        assertThat(reloaded.getAccountType()).isEqualTo(AccountType.CHECKING);
        assertThat(reloaded.getParentId()).isEqualTo(savedInstitution.getId());
    }

    private Account account(String name, AccountType type, BigDecimal balance) {
        Account account = new Account();
        account.setName(name);
        account.setAccountType(type);
        account.setCurrency("EUR");
        account.setInitialBalance(balance);
        account.setCurrentBalance(balance);
        return account;
    }
}
