package de.in.mp.importer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.in.mp.finance.Account;
import de.in.mp.finance.AccountRepository;
import de.in.mp.finance.AccountSnapshotService;
import de.in.mp.finance.AccountType;
import de.in.mp.finance.SnapshotValue;
import de.in.mp.importer.StatementMatch.MatchSource;

/**
 * Imports closing balances from bank statement PDFs.
 * <p>
 * The import is a two-step process: {@link #analyze} extracts all accounts of
 * a statement and resolves them to existing accounts, {@link #apply} records
 * the confirmed balances. When the user assigns an account manually, the
 * entry's import key is stored at the account so that the next statement of
 * the same kind resolves automatically. Several statement accounts assigned
 * to the same target account and date (e.g. a savings book and a call money
 * account tracked as one position) are summed up.
 */
@Service
public class StatementImportService {

    private static final String NOTE_PREFIX = "PDF: ";

    private final List<BankStatementParser> parsers;
    private final PdfTextExtractor textExtractor;
    private final AccountRepository accountRepository;
    private final AccountSnapshotService snapshotService;

    public StatementImportService(List<BankStatementParser> parsers, PdfTextExtractor textExtractor,
            AccountRepository accountRepository, AccountSnapshotService snapshotService) {
        this.parsers = parsers;
        this.textExtractor = textExtractor;
        this.accountRepository = accountRepository;
        this.snapshotService = snapshotService;
    }

    @Transactional(readOnly = true)
    public List<StatementMatch> analyze(String fileName, byte[] pdfBytes) {
        return analyzeText(fileName, textExtractor.extract(pdfBytes));
    }

    @Transactional(readOnly = true)
    public List<StatementMatch> analyzeText(String fileName, String text) {
        BankStatementParser parser = parsers.stream()
                .filter(candidate -> candidate.supports(text))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported statement format: " + fileName));

        List<Account> accounts = accountRepository.findAll().stream()
                .filter(account -> account.getAccountType() != AccountType.INSTITUTION)
                .toList();
        List<StatementMatch> matches = new ArrayList<>();
        for (StatementEntry entry : parser.parse(text)) {
            matches.add(resolve(fileName, entry, accounts));
        }
        return matches;
    }

    /**
     * Records the balances of all assigned matches. Assignments without a
     * target account are skipped.
     *
     * @return number of recorded snapshots
     */
    @Transactional
    public int apply(List<StatementAssignment> assignments) {
        Map<SnapshotKey, SnapshotValue> aggregated = new LinkedHashMap<>();
        for (StatementAssignment assignment : assignments) {
            if (assignment.accountId() == null) {
                continue;
            }
            Account account = accountRepository.findById(assignment.accountId())
                    .orElseThrow(() -> new IllegalArgumentException("Account not found: " + assignment.accountId()));
            StatementEntry entry = assignment.entry();
            rememberAssignment(account, entry);

            SnapshotValue value = new SnapshotValue(entry.closingDate(), entry.closingBalance(), entry.inflow(),
                    entry.outflow(), NOTE_PREFIX + assignment.fileName());
            aggregated.merge(new SnapshotKey(account.getId(), entry.closingDate()), value,
                    StatementImportService::sum);
        }
        aggregated.forEach((key, value) -> snapshotService.recordSnapshot(key.accountId(), value));
        return aggregated.size();
    }

    static StatementMatch resolve(String fileName, StatementEntry entry, List<Account> accounts) {
        Optional<Account> byKey = accounts.stream()
                .filter(account -> account.hasImportKey(entry.importKey()))
                .findFirst();
        if (byKey.isPresent()) {
            return new StatementMatch(fileName, entry, byKey.get(), MatchSource.IMPORT_KEY);
        }

        Optional<Account> byNumber = accounts.stream()
                .filter(account -> matchesAccountNumber(account, entry))
                .findFirst();
        if (byNumber.isPresent()) {
            return new StatementMatch(fileName, entry, byNumber.get(), MatchSource.ACCOUNT_NUMBER);
        }

        if (entry.iban() == null && entry.accountNumber() == null) {
            List<Account> byHolder = accounts.stream()
                    .filter(account -> matchesHolder(account, entry))
                    .toList();
            if (byHolder.size() == 1) {
                return new StatementMatch(fileName, entry, byHolder.get(0), MatchSource.HOLDER);
            }
        }
        return new StatementMatch(fileName, entry, null, MatchSource.NONE);
    }

    static boolean matchesAccountNumber(Account account, StatementEntry entry) {
        String stored = normalizeNumber(account.getAccountNumber());
        if (stored == null) {
            return false;
        }
        if (entry.iban() != null && stored.equals(entry.iban())) {
            return true;
        }
        String number = normalizeNumber(entry.accountNumber());
        if (number == null) {
            return false;
        }
        return stored.equals(number) || (entry.iban() == null && isIban(stored) && stored.endsWith(number));
    }

    private static boolean matchesHolder(Account account, StatementEntry entry) {
        return account.getAccountType() == entry.suggestedType()
                && equalsIgnoreCase(account.getAccountHolder(), entry.accountHolder())
                && containsIgnoreCase(account.getBankName(), entry.bank())
                && normalizeNumber(account.getAccountNumber()) == null;
    }

    /**
     * Fills missing master data from the statement and fixes assignments
     * that cannot be derived from the account number.
     */
    private void rememberAssignment(Account account, StatementEntry entry) {
        boolean changed = false;
        if (normalizeNumber(account.getAccountNumber()) == null && entry.iban() != null) {
            account.setAccountNumber(entry.iban());
            changed = true;
        }
        if (isBlank(account.getAccountHolder()) && !isBlank(entry.accountHolder())) {
            account.setAccountHolder(entry.accountHolder());
            changed = true;
        }
        if (!matchesAccountNumber(account, entry) && !account.hasImportKey(entry.importKey())) {
            account.addImportKey(entry.importKey());
            changed = true;
        }
        if (changed) {
            accountRepository.save(account);
        }
    }

    private static SnapshotValue sum(SnapshotValue a, SnapshotValue b) {
        return new SnapshotValue(a.date(), a.balance().add(b.balance()), addNullable(a.inflow(), b.inflow()),
                addNullable(a.outflow(), b.outflow()), a.notes());
    }

    private static BigDecimal addNullable(BigDecimal a, BigDecimal b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : a.add(b);
    }

    private static String normalizeNumber(String value) {
        if (isBlank(value)) {
            return null;
        }
        return value.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
    }

    private static boolean isIban(String value) {
        return value.length() > 2 && Character.isLetter(value.charAt(0)) && Character.isLetter(value.charAt(1));
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        return haystack != null && needle != null
                && haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record SnapshotKey(UUID accountId, LocalDate date) {
    }

    /**
     * User-confirmed target account of a statement entry.
     */
    public record StatementAssignment(String fileName, StatementEntry entry, UUID accountId) {
    }
}
