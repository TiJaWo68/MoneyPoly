package de.in.mp.finance.banksync;

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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import de.in.mp.finance.Account;
import de.in.mp.finance.AccountRepository;
import de.in.mp.finance.AccountSnapshot;
import de.in.mp.finance.AccountSnapshotService;
import de.in.mp.finance.AccountType;
import de.in.mp.finance.ClassificationRule;
import de.in.mp.finance.ClassificationRuleRepository;
import de.in.mp.finance.FlowType;
import de.in.mp.finance.Household;
import de.in.mp.finance.HouseholdService;
import de.in.mp.finance.SnapshotValue;

class BankSyncServiceTest {

    private BankConnectionRepository connectionRepository;
    private AccountRepository accountRepository;
    private AccountSnapshotService snapshotService;
    private ClassificationRuleRepository ruleRepository;
    private SessionCryptoService cryptoService;
    private HouseholdService householdService;
    private BankSyncProvider enableBankingProvider;
    private BankSyncProvider yaxiProvider;
    private BankSyncService service;

    private UUID householdId;

    @BeforeEach
    void setUp() {
        connectionRepository = mock(BankConnectionRepository.class);
        accountRepository = mock(AccountRepository.class);
        snapshotService = mock(AccountSnapshotService.class);
        ruleRepository = mock(ClassificationRuleRepository.class);
        cryptoService = new SessionCryptoService("test-master-crypto-key");
        householdService = mock(HouseholdService.class);
        enableBankingProvider = mock(BankSyncProvider.class);
        yaxiProvider = mock(BankSyncProvider.class);

        when(enableBankingProvider.providerId()).thenReturn("ENABLE_BANKING");
        when(enableBankingProvider.displayName()).thenReturn("Enable Banking");
        when(enableBankingProvider.isConfigured()).thenReturn(true);
        when(enableBankingProvider.supportsBackgroundSync()).thenReturn(true);

        when(yaxiProvider.providerId()).thenReturn("YAXI");
        when(yaxiProvider.displayName()).thenReturn("YAXI");
        when(yaxiProvider.isConfigured()).thenReturn(true);
        when(yaxiProvider.supportsBackgroundSync()).thenReturn(false);

        when(connectionRepository.save(any(BankConnection.class))).thenAnswer(inv -> {
            BankConnection conn = inv.getArgument(0);
            if (conn.getId() == null) {
                conn.setId(UUID.randomUUID());
            }
            return conn;
        });
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        when(snapshotService.recordConsolidatedMonthlySnapshot(any(), any())).thenAnswer(inv -> {
            SnapshotValue val = inv.getArgument(1);
            AccountSnapshot snap = new AccountSnapshot();
            snap.setId(UUID.randomUUID());
            snap.setAccountId(inv.getArgument(0));
            snap.setSnapshotDate(val.date());
            snap.setBalance(val.balance());
            snap.setInflow(val.inflow());
            snap.setOutflow(val.outflow());
            snap.setNotes(val.notes());
            return snap;
        });

        householdId = UUID.randomUUID();
        when(householdService.resolveEffectiveHouseholdId(any())).thenAnswer(inv -> {
            UUID req = inv.getArgument(0);
            return req != null ? req : householdId;
        });

        service = new BankSyncService(
                connectionRepository,
                accountRepository,
                snapshotService,
                ruleRepository,
                cryptoService,
                List.of(enableBankingProvider, yaxiProvider),
                householdService);
    }

    @Test
    void initiatesAndCompletesConnectionWithEncryptedPayloadAndIbanAutoMatch() {
        BankInstitution c24 = new BankInstitution("c24", "C24 Bank", "C24BDEFF", "DE");
        OffsetDateTime expires = OffsetDateTime.now().plusDays(180);
        when(enableBankingProvider.initiateAuthorization(eq(c24), eq("https://app/cb"), any()))
                .thenReturn(new AuthorizationInitiation(
                        "https://bank/login", "st-1", "{\"authorization_id\":\"auth-1\"}", expires));

        ConnectionInitiationResult initiated = service.initiateConnection(
                householdId, "ENABLE_BANKING", c24, "https://app/cb");

        assertThat(initiated.redirectUrl()).isEqualTo("https://bank/login");
        assertThat(initiated.connection().getStatus()).isEqualTo(BankConnectionStatus.PENDING);
        assertThat(initiated.connection().getEncryptedSessionPayload())
                .doesNotContain("auth-1");

        UUID connId = initiated.connection().getId();
        when(connectionRepository.findById(connId)).thenReturn(Optional.of(initiated.connection()));

        Account localChecking = account("Giro", "DE12 5001 0517 5407 3249 31");
        when(accountRepository.findByHouseholdIdAndActiveTrueOrderByAccountTypeAscNameAsc(householdId))
                .thenReturn(List.of(localChecking));
        when(enableBankingProvider.completeAuthorization("code-99", "{\"authorization_id\":\"auth-1\"}"))
                .thenReturn(new AuthorizedSession(
                        "{\"session_id\":\"sess-real-secret\"}",
                        expires,
                        List.of(new ExternalBankAccount(
                                "ext-c24-1", "DE12500105175407324931", "Smartkonto", "Till", "EUR",
                                AccountType.CHECKING))));

        AuthorizedSession completed = service.completeConnection(connId, "code-99");

        assertThat(completed.accounts()).hasSize(1);
        assertThat(initiated.connection().getStatus()).isEqualTo(BankConnectionStatus.ACTIVE);
        assertThat(initiated.connection().getEncryptedSessionPayload()).doesNotContain("sess-real-secret");
        assertThat(cryptoService.decrypt(initiated.connection().getEncryptedSessionPayload()))
                .contains("sess-real-secret");
        assertThat(localChecking.getBankConnectionId()).isEqualTo(connId);
        assertThat(localChecking.getExternalAccountId()).isEqualTo("ext-c24-1");
    }

    @Test
    void deduplicatesTransactionsFiltersSavingsTransitAndConsolidatesMonthlySnapshot() {
        UUID connId = UUID.randomUUID();
        BankConnection connection = activeConnection(connId, "ENABLE_BANKING", OffsetDateTime.now().plusDays(120));
        Account checking = account("Giro", "DE12500105175407324931");
        checking.setBankConnectionId(connId);
        checking.setExternalAccountId("ext-1");

        Account savings = account("Tagesgeld", "DE99500105175407324932");

        ClassificationRule savingsRule = new ClassificationRule();
        savingsRule.setPriority(10);
        savingsRule.setMatchPurposeRegex("Umbuchung Tagesgeld");
        savingsRule.setTargetFlow(FlowType.SAVINGS_TRANSIT);

        when(accountRepository.findById(checking.getId())).thenReturn(Optional.of(checking));
        when(connectionRepository.findById(connId)).thenReturn(Optional.of(connection));
        when(ruleRepository.findByHouseholdIdOrderByPriorityAsc(householdId)).thenReturn(List.of(savingsRule));
        when(accountRepository.findByHouseholdIdAndActiveTrueOrderByAccountTypeAscNameAsc(householdId))
                .thenReturn(List.of(checking, savings));

        LocalDate refDate = LocalDate.of(2026, 11, 16);
        List<ExternalTransaction> transactions = List.of(
                // Salary
                new ExternalTransaction("tx-salary", LocalDate.of(2026, 11, 1),
                        new BigDecimal("3500.00"), "Employer", "DE001", "Gehalt"),
                // Duplicate by primary externalTransactionId
                new ExternalTransaction("tx-salary", LocalDate.of(2026, 11, 1),
                        new BigDecimal("3500.00"), "Employer", "DE001", "Gehalt"),
                // Supermarket purchase
                new ExternalTransaction("tx-rewe", LocalDate.of(2026, 11, 4),
                        new BigDecimal("-120.50"), "REWE", "DE002", "Einkauf Filiale 5"),
                // Secondary duplicate (no external ID, same date + amount + normalized purpose)
                new ExternalTransaction(null, LocalDate.of(2026, 11, 4),
                        new BigDecimal("-120.50"), "REWE", "DE002", "  einkauf   filiale 5 "),
                // Distinct second coffee with different external ID on same day
                new ExternalTransaction("tx-coffee-1", LocalDate.of(2026, 11, 5),
                        new BigDecimal("-4.50"), "Cafe", "DE003", "Espresso"),
                new ExternalTransaction("tx-coffee-2", LocalDate.of(2026, 11, 5),
                        new BigDecimal("-4.50"), "Cafe", "DE003", "Espresso"),
                // Excluded by SAVINGS_TRANSIT ClassificationRule
                new ExternalTransaction("tx-transit-rule", LocalDate.of(2026, 11, 6),
                        new BigDecimal("-500.00"), "External", "DE777", "Umbuchung Tagesgeld"),
                // Excluded by intra-household IBAN match (transfer to own savings account)
                new ExternalTransaction("tx-transit-iban", LocalDate.of(2026, 11, 7),
                        new BigDecimal("-250.00"), "Own Savings", "DE99 5001 0517 5407 3249 32", "Sparen")
        );

        when(enableBankingProvider.fetchAccountSyncData(
                "{\"session_id\":\"s1\"}", "ext-1", LocalDate.of(2026, 11, 1), refDate))
                .thenReturn(new ExternalAccountSyncData(
                        "ext-1", refDate, new BigDecimal("4250.00"), null, null, transactions));

        AccountSnapshot snapshot = service.syncAccount(checking.getId(), refDate);

        assertThat(snapshot.getBalance()).isEqualByComparingTo("4250.00");
        assertThat(snapshot.getInflow()).isEqualByComparingTo("3500.00");
        // 120.50 + 4.50 + 4.50 = 129.50 (both transit items and duplicates excluded)
        assertThat(snapshot.getOutflow()).isEqualByComparingTo("129.50");
        assertThat(connection.getLastSyncAt()).isNotNull();
        assertThat(connection.getLastError()).isNull();

        ArgumentCaptor<SnapshotValue> valueCaptor = ArgumentCaptor.forClass(SnapshotValue.class);
        verify(snapshotService).recordConsolidatedMonthlySnapshot(eq(checking.getId()), valueCaptor.capture());
        assertThat(valueCaptor.getValue().notes()).isEqualTo("Open-Banking: C24 Bank");
    }

    @Test
    void marksConnectionExpiredAndBlocksSyncWhenConsentElapsed() {
        UUID connId = UUID.randomUUID();
        BankConnection expired = activeConnection(connId, "ENABLE_BANKING", OffsetDateTime.now().minusDays(1));
        Account checking = account("Giro", "DE12500105175407324931");
        checking.setBankConnectionId(connId);
        checking.setExternalAccountId("ext-1");

        when(accountRepository.findById(checking.getId())).thenReturn(Optional.of(checking));
        when(connectionRepository.findById(connId)).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.syncAccount(checking.getId(), LocalDate.of(2026, 11, 16)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired");
        assertThat(expired.getStatus()).isEqualTo(BankConnectionStatus.EXPIRED);
        verify(enableBankingProvider, never()).fetchAccountSyncData(any(), any(), any(), any());
    }

    @Test
    void linkAccountDisablesAutoSyncForInteractiveProviderAndScheduledSyncSkipsDemoHousehold() {
        UUID yaxiConnId = UUID.randomUUID();
        BankConnection yaxiConn = activeConnection(yaxiConnId, "YAXI", OffsetDateTime.now().plusDays(30));
        Account trAccount = account("Depot Cash", "DE55500105175407324931");
        when(accountRepository.findById(trAccount.getId())).thenReturn(Optional.of(trAccount));
        when(connectionRepository.findById(yaxiConnId)).thenReturn(Optional.of(yaxiConn));

        service.linkAccount(trAccount.getId(), yaxiConnId, "tr-1", true);
        assertThat(trAccount.isAutoSyncEnabled()).isFalse();

        UUID ebConnId = UUID.randomUUID();
        BankConnection ebConn = activeConnection(ebConnId, "ENABLE_BANKING", OffsetDateTime.now().plusDays(90));
        Account demoAccount = account("Demo Giro", "DE00000000000000000001");
        UUID demoHouseholdId = UUID.randomUUID();
        demoAccount.setHouseholdId(demoHouseholdId);
        demoAccount.setBankConnectionId(ebConnId);
        demoAccount.setExternalAccountId("ext-demo");
        demoAccount.setAutoSyncEnabled(true);

        Household demoHousehold = new Household();
        demoHousehold.setId(demoHouseholdId);
        demoHousehold.setDemo(true);
        when(householdService.findById(demoHouseholdId)).thenReturn(Optional.of(demoHousehold));
        when(accountRepository.findByActiveTrueAndAutoSyncEnabledTrueAndBankConnectionIdIsNotNull())
                .thenReturn(List.of(demoAccount));

        BankSyncResult scheduled = service.runScheduledSync(LocalDate.of(2026, 11, 16));
        assertThat(scheduled.syncedAccounts()).isZero();
        assertThat(scheduled.skippedAccounts()).isEqualTo(1);
    }

    @Test
    void deleteConnectionUnlinksAccountsBeforeDeletion() {
        UUID connId = UUID.randomUUID();
        BankConnection conn = activeConnection(connId, "ENABLE_BANKING", OffsetDateTime.now().plusDays(60));
        Account linked = account("Giro", "DE12");
        linked.setBankConnectionId(connId);
        linked.setExternalAccountId("ext-1");
        linked.setAutoSyncEnabled(true);

        when(connectionRepository.findById(connId)).thenReturn(Optional.of(conn));
        when(accountRepository.findByBankConnectionId(connId)).thenReturn(List.of(linked));

        service.deleteConnection(connId);

        assertThat(linked.isOpenBankingLinked()).isFalse();
        assertThat(linked.isAutoSyncEnabled()).isFalse();
        verify(accountRepository).saveAll(List.of(linked));
        verify(connectionRepository).delete(conn);
    }

    @Test
    void extractsCodeOrTicketFromFullCallbackUrl() {
        assertThat(BankSyncService.extractCodeOrTicket(
                "https://localhost:8080/finance?state=b6293ad8-75cf-480d-bbaa-e93791e3a486&code=95547f6e-0153-43c7-8235-5c2fa1f36ffe"))
                .isEqualTo("95547f6e-0153-43c7-8235-5c2fa1f36ffe");
        assertThat(BankSyncService.extractCodeOrTicket("https://example.test/cb?ticket=tk-123"))
                .isEqualTo("tk-123");
        assertThat(BankSyncService.extractCodeOrTicket("  raw-code-456  "))
                .isEqualTo("raw-code-456");
        assertThatThrownBy(() -> BankSyncService.extractCodeOrTicket("https://localhost:8080/finance?state=only"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void completesAndRecordsErrorForPendingConnectionMatchedByOAuthState() {
        UUID connId = UUID.randomUUID();
        BankConnection pending = new BankConnection();
        pending.setId(connId);
        pending.setHouseholdId(householdId);
        pending.setProviderId("ENABLE_BANKING");
        pending.setInstitutionName("Trade Republic");
        pending.setStatus(BankConnectionStatus.PENDING);
        pending.setEncryptedSessionPayload(
                cryptoService.encrypt("{\"authorization_id\":\"auth-tr\",\"state\":\"state-tr-1\"}"));

        when(connectionRepository.findByStatus(BankConnectionStatus.PENDING)).thenReturn(List.of(pending));
        when(connectionRepository.findById(connId)).thenReturn(Optional.of(pending));
        when(accountRepository.findByHouseholdIdAndActiveTrueOrderByAccountTypeAscNameAsc(householdId))
                .thenReturn(List.of());
        when(enableBankingProvider.completeAuthorization(
                "code-tr", "{\"authorization_id\":\"auth-tr\",\"state\":\"state-tr-1\"}"))
                .thenReturn(new AuthorizedSession(
                        "{\"session_id\":\"sess-tr\"}",
                        OffsetDateTime.now().plusDays(90),
                        List.of()));

        AuthorizedSession completed = service.completeConnectionByState(householdId, "state-tr-1", "code-tr");
        assertThat(completed.sessionPayload()).contains("sess-tr");
        assertThat(pending.getStatus()).isEqualTo(BankConnectionStatus.ACTIVE);

        BankConnection pendingError = new BankConnection();
        pendingError.setId(UUID.randomUUID());
        pendingError.setHouseholdId(householdId);
        pendingError.setProviderId("ENABLE_BANKING");
        pendingError.setInstitutionName("Trade Republic");
        pendingError.setStatus(BankConnectionStatus.PENDING);
        pendingError.setEncryptedSessionPayload(
                cryptoService.encrypt("{\"authorization_id\":\"auth-err\",\"state\":\"state-tr-2\"}"));
        when(connectionRepository.findByStatus(BankConnectionStatus.PENDING)).thenReturn(List.of(pendingError));

        service.recordAuthorizationErrorByState(householdId, "state-tr-2", "access_denied: Cancelled in app");
        assertThat(pendingError.getStatus()).isEqualTo(BankConnectionStatus.ERROR);
        assertThat(pendingError.getLastError()).isEqualTo("access_denied: Cancelled in app");
    }

    private Account account(String name, String iban) {
        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setHouseholdId(householdId);
        account.setName(name);
        account.setAccountType(AccountType.CHECKING);
        account.setAccountNumber(iban);
        account.setActive(true);
        return account;
    }

    private BankConnection activeConnection(UUID id, String providerId, OffsetDateTime expiresAt) {
        BankConnection connection = new BankConnection();
        connection.setId(id);
        connection.setHouseholdId(householdId);
        connection.setProviderId(providerId);
        connection.setInstitutionName("C24 Bank");
        connection.setStatus(BankConnectionStatus.ACTIVE);
        connection.setConsentExpiresAt(expiresAt);
        connection.setEncryptedSessionPayload(cryptoService.encrypt("{\"session_id\":\"s1\"}"));
        return connection;
    }
}
