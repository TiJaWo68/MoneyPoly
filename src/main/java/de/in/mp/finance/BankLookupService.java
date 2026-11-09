package de.in.mp.finance;

import java.util.Optional;

import org.iban4j.CountryCode;
import org.iban4j.Iban;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves a bank name from an IBAN.
 * <p>
 * The German bank code (BLZ) is extracted with IBAN4j and looked up in the
 * local {@link Bank} cache first. On a cache miss the current master data is
 * fetched from OpenIBAN and persisted, so subsequent lookups work offline.
 */
@Service
public class BankLookupService {

    private final BankRepository bankRepository;
    private final OpenIbanClient openIbanClient;

    public BankLookupService(BankRepository bankRepository, OpenIbanClient openIbanClient) {
        this.bankRepository = bankRepository;
        this.openIbanClient = openIbanClient;
    }

    @Transactional
    public Optional<String> findBankName(String iban) {
        String bankCode = extractGermanBankCode(iban);
        if (bankCode == null) {
            return Optional.empty();
        }

        Optional<Bank> cached = bankRepository.findByBlz(bankCode);
        if (cached.isPresent()) {
            return cached.map(Bank::getName);
        }

        return openIbanClient.lookup(iban)
                .filter(info -> bankCode.equals(info.bankCode()))
                .map(this::persist)
                .map(Bank::getName);
    }

    private String extractGermanBankCode(String iban) {
        if (iban == null || iban.isBlank()) {
            return null;
        }

        Iban parsed;
        try {
            parsed = Iban.valueOf(iban.trim());
        } catch (Exception exception) {
            return null;
        }

        if (parsed.getCountryCode() != CountryCode.DE) {
            return null;
        }

        String bankCode = parsed.getBankCode();
        return (bankCode == null || bankCode.isBlank()) ? null : bankCode;
    }

    private Bank persist(OpenIbanClient.BankInfo info) {
        Bank bank = new Bank();
        bank.setBlz(info.bankCode());
        bank.setName(info.name());
        bank.setBic(info.bic());
        bank.setZip(info.zip());
        bank.setCity(info.city());
        return bankRepository.save(bank);
    }
}
