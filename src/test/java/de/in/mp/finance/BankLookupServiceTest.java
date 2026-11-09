package de.in.mp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BankLookupService}.
 */
class BankLookupServiceTest {

    private final BankLookupService service = new BankLookupService();

    @Test
    void returnsEmptyForBlankIban() {
        assertThat(service.findBankName(null)).isEmpty();
        assertThat(service.findBankName("   ")).isEmpty();
    }

    @Test
    void returnsEmptyForInvalidIban() {
        assertThat(service.findBankName("not-an-iban")).isEmpty();
    }

    @Test
    void returnsEmptyForNonGermanIban() {
        assertThat(service.findBankName("FR1420041010050500013M02606")).isEmpty();
    }

    @Test
    void returnsBankNameForKnownGermanIban() {
        assertThat(service.findBankName("DE89370400440532013000"))
                .isPresent()
                .hasValueSatisfying(name -> assertThat(name).isNotBlank());
    }
}
