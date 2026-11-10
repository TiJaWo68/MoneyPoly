package de.in.mp.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import de.in.mp.finance.Account;
import de.in.mp.finance.AccountRepository;
import de.in.mp.finance.AccountSnapshotService;
import de.in.mp.finance.AccountType;
import de.in.mp.finance.SnapshotValue;
import de.in.mp.importer.StatementImportService.StatementAssignment;
import de.in.mp.importer.StatementMatch.MatchSource;

/**
 * Tests account resolution and snapshot recording of the statement import
 * with fictional accounts.
 */
class StatementImportServiceTest {

    private static final LocalDate CLOSING = LocalDate.of(2026, 10, 31);
    private static final String IBAN = "DE02120300000000202051";

    private AccountRepository accountRepository;
    private AccountSnapshotService snapshotService;
    private StatementImportService service;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        snapshotService = mock(AccountSnapshotService.class);
        service = new StatementImportService(
                List.of(new C24StatementParser(), new TargobankStatementParser()),
                new PdfTextExtractor(), accountRepository, snapshotService);
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void resolvesByIbanIgnoringSpaces() {
        Account account = account("Giro", AccountType.CHECKING, "DE02 1203 0000 0000 2020 51", null);

        StatementMatch match = StatementImportService.resolve("a.pdf", ibanEntry(), List.of(account));

        assertThat(match.account()).isSameAs(account);
        assertThat(match.source()).isEqualTo(MatchSource.ACCOUNT_NUMBER);
    }

    @Test
    void resolvesPlainAccountNumberAgainstStoredIban() {
        Account account = account("Spar", AccountType.SAVINGS, "DE12345678902222222222", null);
        StatementEntry entry = entry(null, "2222222222", AccountType.SAVINGS, "Bank|2222222222");

        assertThat(StatementImportService.resolve("a.pdf", entry, List.of(account)).account()).isSameAs(account);
    }

    @Test
    void prefersRememberedImportKey() {
        Account byNumber = account("Giro", AccountType.CHECKING, IBAN, null);
        Account remembered = account("Combined", AccountType.SAVINGS, null, null);
        remembered.addImportKey("IBAN|" + IBAN);

        StatementMatch match = StatementImportService.resolve("a.pdf", ibanEntry(), List.of(byNumber, remembered));

        assertThat(match.account()).isSameAs(remembered);
        assertThat(match.source()).isEqualTo(MatchSource.IMPORT_KEY);
    }

    @Test
    void resolvesIbanlessEntryByUniqueHolderBankAndType() {
        Account pocket = account("Pocket", AccountType.SAVINGS, null, "Erika Mustermann");
        pocket.setBankName("C24 Bank");
        Account other = account("Pocket 2", AccountType.SAVINGS, null, "Max Mustermann");
        other.setBankName("C24 Bank");
        StatementEntry entry = new StatementEntry("C24", "Tagesgeld", AccountType.SAVINGS, null, null,
                "Erika Mustermann", CLOSING, BigDecimal.TEN, null, null, "C24|erika mustermann|tagesgeld");

        StatementMatch match = StatementImportService.resolve("a.pdf", entry, List.of(pocket, other));

        assertThat(match.account()).isSameAs(pocket);
        assertThat(match.source()).isEqualTo(MatchSource.HOLDER);
    }

    @Test
    void leavesAmbiguousOrUnknownEntriesUnresolved() {
        StatementMatch match = StatementImportService.resolve("a.pdf", ibanEntry(),
                List.of(account("Other", AccountType.CHECKING, "DE99999999999999999999", null)));

        assertThat(match.resolved()).isFalse();
        assertThat(match.source()).isEqualTo(MatchSource.NONE);
    }

    @Test
    void applyRemembersManualAssignmentAndRecordsSnapshot() {
        Account target = account("Pocket", AccountType.SAVINGS, null, null);
        when(accountRepository.findById(target.getId())).thenReturn(Optional.of(target));
        StatementEntry entry = new StatementEntry("C24", "Tagesgeld", AccountType.SAVINGS, null, null,
                "Erika Mustermann", CLOSING, new BigDecimal("42.00"), BigDecimal.ONE, BigDecimal.ZERO, "C24|key");

        int recorded = service.apply(List.of(new StatementAssignment("pocket.pdf", entry, target.getId())));

        assertThat(recorded).isEqualTo(1);
        assertThat(target.hasImportKey("C24|key")).isTrue();
        assertThat(target.getAccountHolder()).isEqualTo("Erika Mustermann");
        ArgumentCaptor<SnapshotValue> value = ArgumentCaptor.forClass(SnapshotValue.class);
        verify(snapshotService).recordSnapshot(eq(target.getId()), value.capture());
        assertThat(value.getValue().balance()).isEqualByComparingTo("42.00");
        assertThat(value.getValue().notes()).isEqualTo("PDF: pocket.pdf");
    }

    @Test
    void applySumsEntriesAssignedToTheSameAccountAndDate() {
        Account combined = account("Savings", AccountType.SAVINGS, null, null);
        when(accountRepository.findById(combined.getId())).thenReturn(Optional.of(combined));
        StatementEntry book = entry(null, "2222222222", AccountType.SAVINGS, "Bank|2222222222");
        StatementEntry callMoney = new StatementEntry("Bank", "Call money", AccountType.SAVINGS, null, "3333333333",
                null, CLOSING, new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, "Bank|3333333333");

        int recorded = service.apply(List.of(
                new StatementAssignment("s.pdf", book, combined.getId()),
                new StatementAssignment("s.pdf", callMoney, combined.getId())));

        assertThat(recorded).isEqualTo(1);
        ArgumentCaptor<SnapshotValue> value = ArgumentCaptor.forClass(SnapshotValue.class);
        verify(snapshotService).recordSnapshot(eq(combined.getId()), value.capture());
        assertThat(value.getValue().balance()).isEqualByComparingTo("101.00");
        assertThat(combined.importKeySet()).containsExactly("Bank|2222222222", "Bank|3333333333");
    }

    @Test
    void applySkipsUnassignedEntries() {
        assertThat(service.apply(List.of(new StatementAssignment("x.pdf", ibanEntry(), null)))).isZero();
        verify(snapshotService, never()).recordSnapshot(any(), any());
    }

    @Test
    void analyzeTextRejectsUnsupportedDocuments() {
        assertThatThrownBy(() -> service.analyzeText("x.pdf", "Some unrelated document"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static StatementEntry ibanEntry() {
        return entry(IBAN, null, AccountType.CHECKING, "IBAN|" + IBAN);
    }

    private static StatementEntry entry(String iban, String number, AccountType type, String key) {
        return new StatementEntry("Bank", "Label", type, iban, number, null, CLOSING, BigDecimal.ONE, null,
                null, key);
    }

    private static Account account(String name, AccountType type, String number, String holder) {
        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setName(name);
        account.setAccountType(type);
        account.setAccountNumber(number);
        account.setAccountHolder(holder);
        return account;
    }
}
