package de.in.mp.finance;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.iban4j.CountryCode;
import org.iban4j.Iban;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Resolves a bank name from an IBAN.
 * <p>
 * Uses IBAN4j to validate the IBAN and to extract the German bank code (BLZ),
 * then maps the BLZ to a human-readable bank name via the bundled Bundesbank
 * bank code file (data/blz.tsv). Resolution is best-effort: invalid IBANs,
 * non-German IBANs and unknown bank codes return an empty result instead of
 * throwing.
 */
@Service
public class BankLookupService {

    private static final String BLZ_RESOURCE = "data/blz.tsv";

    private volatile Map<String, String> bankNamesByBlz;

    public Optional<String> findBankName(String iban) {
        if (iban == null || iban.isBlank()) {
            return Optional.empty();
        }

        Iban parsed;
        try {
            parsed = Iban.valueOf(iban.trim());
        } catch (Exception exception) {
            return Optional.empty();
        }

        if (parsed.getCountryCode() != CountryCode.DE) {
            return Optional.empty();
        }

        String bankCode = parsed.getBankCode();
        if (bankCode == null || bankCode.isBlank()) {
            return Optional.empty();
        }

        return Optional.ofNullable(loadBankNames().get(bankCode));
    }

    private Map<String, String> loadBankNames() {
        Map<String, String> result = bankNamesByBlz;
        if (result == null) {
            synchronized (this) {
                result = bankNamesByBlz;
                if (result == null) {
                    result = parseBankNames();
                    bankNamesByBlz = result;
                }
            }
        }
        return result;
    }

    private Map<String, String> parseBankNames() {
        Map<String, String> names = new HashMap<>();
        try (InputStream stream = new ClassPathResource(BLZ_RESOURCE).getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] columns = line.split("\t", -1);
                if (columns.length < 3) {
                    continue;
                }
                String blz = columns[0].trim();
                String name = columns[2].trim();
                if (!blz.isEmpty() && !name.isEmpty()) {
                    names.putIfAbsent(blz, name);
                }
            }
        } catch (IOException exception) {
            // Best-effort lookup: an unreadable directory must not break the editor.
        }
        return names;
    }
}
