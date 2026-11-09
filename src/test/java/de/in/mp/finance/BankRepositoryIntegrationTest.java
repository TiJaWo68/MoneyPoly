package de.in.mp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Persistence integration test for the {@link Bank} cache table. Runs all
 * Flyway migrations against a real PostgreSQL instance and verifies that bank
 * master data can be persisted and reloaded by BLZ. Tagged {@code integration}
 * so it is skipped locally without Docker and executed via the
 * {@code integration-tests} Maven profile.
 */
@SpringBootTest
@Testcontainers
@Tag("integration")
class BankRepositoryIntegrationTest {

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
    private BankRepository bankRepository;

    @Test
    void persistsBankAndFindsByBlz() {
        Bank bank = new Bank();
        bank.setBlz("37040044");
        bank.setName("Commerzbank");
        bank.setBic("COBADEFFXXX");
        bank.setZip("50447");
        bank.setCity("Köln");

        Bank saved = bankRepository.save(bank);

        assertThat(saved.getId()).isNotNull();
        assertThat(bankRepository.findByBlz("37040044"))
                .isPresent()
                .hasValueSatisfying(reloaded -> assertThat(reloaded.getName()).isEqualTo("Commerzbank"));
    }
}
