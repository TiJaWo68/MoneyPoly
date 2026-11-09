package de.in.mp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BankLookupService} using a mocked repository and
 * OpenIBAN client.
 */
class BankLookupServiceTest {

    private static final String IBAN = "DE89370400440532013000";
    private static final String BLZ = "37040044";

    private BankRepository bankRepository;
    private OpenIbanClient openIbanClient;
    private BankLookupService service;

    @BeforeEach
    void setUp() {
        bankRepository = mock(BankRepository.class);
        openIbanClient = mock(OpenIbanClient.class);
        service = new BankLookupService(bankRepository, openIbanClient);
    }

    @Test
    void returnsCachedNameWithoutCallingOpenIban() {
        when(bankRepository.findByBlz(BLZ)).thenReturn(Optional.of(bank("Commerzbank")));

        assertThat(service.findBankName(IBAN)).contains("Commerzbank");
        verifyNoInteractions(openIbanClient);
    }

    @Test
    void looksUpAndPersistsOnCacheMiss() {
        when(bankRepository.findByBlz(BLZ)).thenReturn(Optional.empty());
        OpenIbanClient.BankInfo info = new OpenIbanClient.BankInfo(BLZ, "Commerzbank", "50447", "Köln", "COBADEFFXXX");
        when(openIbanClient.lookup(IBAN)).thenReturn(Optional.of(info));
        when(bankRepository.save(any(Bank.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.findBankName(IBAN)).contains("Commerzbank");
        verify(bankRepository).save(any(Bank.class));
    }

    @Test
    void returnsEmptyForInvalidIban() {
        assertThat(service.findBankName("not-an-iban")).isEmpty();
        verifyNoInteractions(bankRepository, openIbanClient);
    }

    @Test
    void returnsEmptyWhenOpenIbanMisses() {
        when(bankRepository.findByBlz(BLZ)).thenReturn(Optional.empty());
        when(openIbanClient.lookup(IBAN)).thenReturn(Optional.empty());

        assertThat(service.findBankName(IBAN)).isEmpty();
        verify(bankRepository, never()).save(any(Bank.class));
    }

    private Bank bank(String name) {
        Bank bank = new Bank();
        bank.setBlz(BLZ);
        bank.setName(name);
        return bank;
    }
}
