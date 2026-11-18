package de.in.mp.finance.banksync;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

/**
 * Orchestrates read-only Open-Banking connections, encrypted session storage,
 * two-stage transaction deduplication, classification-rule filtering
 * (including {@code SAVINGS_TRANSIT} / {@code INTERNAL_TRANSFER}), and
 * consolidated monthly {@link AccountSnapshot} updates.
 */
@Service
public class BankSyncService {

    public static final int DEFAULT_CONSENT_WARNING_DAYS = 14;
    private static final int ERROR_MAX_LENGTH = 500;
    private static final String SNAPSHOT_NOTE_PREFIX = "Open-Banking: ";

    private static final Logger LOG = LoggerFactory.getLogger(BankSyncService.class);

    private final BankConnectionRepository connectionRepository;
    private final AccountRepository accountRepository;
    private final AccountSnapshotService snapshotService;
    private final ClassificationRuleRepository classificationRuleRepository;
    private final SessionCryptoService sessionCryptoService;
    private final Map<String, BankSyncProvider> providersById;
    private final HouseholdService householdService;

    public BankSyncService(
            BankConnectionRepository connectionRepository,
            AccountRepository accountRepository,
            AccountSnapshotService snapshotService,
            ClassificationRuleRepository classificationRuleRepository,
            SessionCryptoService sessionCryptoService,
            List<BankSyncProvider> providers) {
        this(connectionRepository, accountRepository, snapshotService, classificationRuleRepository,
                sessionCryptoService, providers, null);
    }

    @Autowired
    public BankSyncService(
            BankConnectionRepository connectionRepository,
            AccountRepository accountRepository,
            AccountSnapshotService snapshotService,
            ClassificationRuleRepository classificationRuleRepository,
            SessionCryptoService sessionCryptoService,
            List<BankSyncProvider> providers,
            @Autowired(required = false) HouseholdService householdService) {
        this.connectionRepository = connectionRepository;
        this.accountRepository = accountRepository;
        this.snapshotService = snapshotService;
        this.classificationRuleRepository = classificationRuleRepository;
        this.sessionCryptoService = sessionCryptoService;
        this.providersById = indexProviders(providers);
        this.householdService = householdService;
    }

    public boolean isEncryptionConfigured() {
        return sessionCryptoService.isConfigured();
    }

    public List<BankSyncProvider> getProviders() {
        return List.copyOf(providersById.values());
    }

    public Optional<BankSyncProvider> findProvider(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(providersById.get(providerId.trim().toUpperCase(Locale.ROOT)));
    }

    @Transactional(readOnly = true)
    public List<BankConnection> findConnectionsForCurrentHousehold() {
        return findConnectionsByHousehold(null);
    }

    @Transactional(readOnly = true)
    public List<BankConnection> findConnectionsByHousehold(UUID householdId) {
        UUID effectiveId = resolveEffectiveHouseholdId(householdId);
        if (effectiveId == null) {
            return List.of();
        }
        return connectionRepository.findByHouseholdIdOrderByInstitutionNameAsc(effectiveId);
    }

    @Transactional
    public ConnectionInitiationResult initiateConnection(
            UUID householdId,
            String providerId,
            BankInstitution institution,
            String callbackUrl) {
        requireEncryptionConfigured();
        UUID effectiveHouseholdId = requireHouseholdId(householdId);
        BankSyncProvider provider = requireConfiguredProvider(providerId);
        if (institution == null || institution.name() == null || institution.name().isBlank()) {
            throw new IllegalArgumentException("Institution is required");
        }
        String state = UUID.randomUUID().toString();
        AuthorizationInitiation initiation = provider.initiateAuthorization(institution, callbackUrl, state);

        BankConnection connection = new BankConnection();
        connection.setHouseholdId(effectiveHouseholdId);
        connection.setProviderId(provider.providerId());
        connection.setInstitutionId(institution.id());
        connection.setInstitutionName(institution.name());
        connection.setStatus(BankConnectionStatus.PENDING);
        connection.setConsentExpiresAt(initiation.consentExpiresAt());
        connection.setEncryptedSessionPayload(sessionCryptoService.encrypt(initiation.sessionPayload()));

        BankConnection saved = connectionRepository.save(connection);
        return new ConnectionInitiationResult(saved, initiation.redirectUrl());
    }

    @Transactional
    public AuthorizedSession completeConnection(UUID connectionId, String codeOrTicket) {
        requireEncryptionConfigured();
        BankConnection connection = requireConnection(connectionId);
        BankSyncProvider provider = requireConfiguredProvider(connection.getProviderId());

        String pendingPayload = sessionCryptoService.decrypt(connection.getEncryptedSessionPayload());
        String resolvedCode = extractCodeOrTicket(codeOrTicket);
        AuthorizedSession authorized = provider.completeAuthorization(resolvedCode, pendingPayload);

        connection.setEncryptedSessionPayload(sessionCryptoService.encrypt(authorized.sessionPayload()));
        connection.setStatus(BankConnectionStatus.ACTIVE);
        connection.setConsentExpiresAt(authorized.consentExpiresAt());
        connection.setLastError(null);
        connectionRepository.save(connection);

        autoMatchAccounts(connection, authorized.accounts());
        return authorized;
    }

    @Transactional(readOnly = true)
    public List<ExternalBankAccount> fetchExternalAccounts(UUID connectionId) {
        requireEncryptionConfigured();
        BankConnection connection = requireConnection(connectionId);
        ensureConsentValid(connection);
        BankSyncProvider provider = requireConfiguredProvider(connection.getProviderId());
        String payload = sessionCryptoService.decrypt(connection.getEncryptedSessionPayload());
        return provider.fetchAccounts(payload);
    }

    @Transactional
    public Account linkAccount(UUID accountId, UUID connectionId, String externalAccountId, boolean autoSyncEnabled) {
        if (externalAccountId == null || externalAccountId.isBlank()) {
            throw new IllegalArgumentException("External account ID is required");
        }
        Account account = requireAccount(accountId);
        if (account.getAccountType() == AccountType.INSTITUTION) {
            throw new IllegalArgumentException("Institutions cannot be linked to Open-Banking accounts");
        }
        BankConnection connection = requireConnection(connectionId);
        if (account.getHouseholdId() != null && connection.getHouseholdId() != null
                && !account.getHouseholdId().equals(connection.getHouseholdId())) {
            throw new IllegalArgumentException("Account and bank connection belong to different households");
        }
        BankSyncProvider provider = requireProvider(connection.getProviderId());
        account.setBankConnectionId(connection.getId());
        account.setExternalAccountId(externalAccountId.trim());
        account.setAutoSyncEnabled(autoSyncEnabled && provider.supportsBackgroundSync());
        return accountRepository.save(account);
    }

    @Transactional
    public Account unlinkAccount(UUID accountId) {
        Account account = requireAccount(accountId);
        account.setBankConnectionId(null);
        account.setExternalAccountId(null);
        account.setAutoSyncEnabled(false);
        return accountRepository.save(account);
    }

    @Transactional
    public void deleteConnection(UUID connectionId) {
        BankConnection connection = requireConnection(connectionId);
        List<Account> linked = accountRepository.findByBankConnectionId(connection.getId());
        for (Account account : linked) {
            account.setBankConnectionId(null);
            account.setExternalAccountId(null);
            account.setAutoSyncEnabled(false);
        }
        if (!linked.isEmpty()) {
            accountRepository.saveAll(linked);
        }
        connectionRepository.delete(connection);
    }

    @Transactional
    public AccountSnapshot syncAccount(UUID accountId, LocalDate referenceDate) {
        requireEncryptionConfigured();
        Account account = requireAccount(accountId);
        if (!account.isOpenBankingLinked()) {
            throw new IllegalStateException("Account is not linked to an Open-Banking connection");
        }
        BankConnection connection = requireConnection(account.getBankConnectionId());
        ensureConsentValid(connection);
        BankSyncProvider provider = requireConfiguredProvider(connection.getProviderId());

        LocalDate effectiveDate = referenceDate != null ? referenceDate : LocalDate.now();
        LocalDate monthStart = effectiveDate.withDayOfMonth(1);
        String payload = sessionCryptoService.decrypt(connection.getEncryptedSessionPayload());

        ExternalAccountSyncData syncData = provider.fetchAccountSyncData(
                payload, account.getExternalAccountId(), monthStart, effectiveDate);
        if (syncData == null || syncData.balance() == null) {
            throw new IllegalStateException("Open-Banking provider returned no balance");
        }

        MonthlyFlows flows = resolveMonthlyFlows(account, syncData, monthStart, effectiveDate);
        LocalDate snapshotDate = syncData.asOfDate() != null ? syncData.asOfDate() : effectiveDate;
        AccountSnapshot snapshot = snapshotService.recordConsolidatedMonthlySnapshot(
                account.getId(),
                new SnapshotValue(
                        snapshotDate,
                        syncData.balance(),
                        flows.inflow(),
                        flows.outflow(),
                        SNAPSHOT_NOTE_PREFIX + connection.getInstitutionName()));

        connection.setLastSyncAt(OffsetDateTime.now());
        connection.setStatus(BankConnectionStatus.ACTIVE);
        connection.setLastError(null);
        connectionRepository.save(connection);
        return snapshot;
    }

    @Transactional
    public BankSyncResult syncConnection(UUID connectionId, LocalDate referenceDate) {
        BankConnection connection = requireConnection(connectionId);
        List<Account> linked = accountRepository.findByBankConnectionId(connection.getId()).stream()
                .filter(Account::isActive)
                .toList();
        return syncAccountBatch(linked, referenceDate, false);
    }

    @Transactional
    public BankSyncResult syncHouseholdAccounts(UUID householdId, LocalDate referenceDate) {
        UUID effectiveId = resolveEffectiveHouseholdId(householdId);
        if (effectiveId == null) {
            return new BankSyncResult(0, 0, 0, List.of());
        }
        List<Account> linkedAccounts = accountRepository.findByHouseholdIdAndActiveTrueOrderByAccountTypeAscNameAsc(effectiveId)
                .stream()
                .filter(Account::isOpenBankingLinked)
                .toList();
        return syncAccountBatch(linkedAccounts, referenceDate, false);
    }

    @Transactional
    public BankSyncResult runScheduledSync(LocalDate referenceDate) {
        if (!sessionCryptoService.isConfigured()) {
            LOG.debug("Skipping scheduled Open-Banking sync because MONEYPOLY_CRYPTO_KEY is not configured");
            return new BankSyncResult(0, 0, 0, List.of());
        }
        List<Account> autoSyncAccounts = accountRepository.findByActiveTrueAndAutoSyncEnabledTrueAndBankConnectionIdIsNotNull();
        return syncAccountBatch(autoSyncAccounts, referenceDate, true);
    }

    private BankSyncResult syncAccountBatch(List<Account> accounts, LocalDate referenceDate, boolean backgroundOnly) {
        int synced = 0;
        int skipped = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        Map<UUID, BankConnection> connectionsById = new HashMap<>();

        for (Account account : accounts) {
            if (!account.isOpenBankingLinked()) {
                skipped++;
                continue;
            }
            if (backgroundOnly && isDemoHousehold(account.getHouseholdId())) {
                skipped++;
                continue;
            }
            BankConnection connection = connectionsById.computeIfAbsent(
                    account.getBankConnectionId(),
                    id -> connectionRepository.findById(id).orElse(null));
            if (connection == null) {
                skipped++;
                continue;
            }
            Optional<BankSyncProvider> providerOpt = findProvider(connection.getProviderId());
            if (providerOpt.isEmpty() || !providerOpt.get().isConfigured()
                    || (backgroundOnly && !providerOpt.get().supportsBackgroundSync())) {
                skipped++;
                continue;
            }
            try {
                syncAccount(account.getId(), referenceDate);
                synced++;
            } catch (RuntimeException ex) {
                failed++;
                String message = account.getName() + ": " + ex.getMessage();
                errors.add(message);
                recordConnectionError(connection, ex);
            }
        }
        return new BankSyncResult(synced, skipped, failed, errors);
    }

    private void recordConnectionError(BankConnection connection, RuntimeException ex) {
        if (connection.isConsentExpired(OffsetDateTime.now())) {
            connection.setStatus(BankConnectionStatus.EXPIRED);
        } else {
            connection.setStatus(BankConnectionStatus.ERROR);
        }
        String msg = ex.getMessage();
        if (msg != null && msg.length() > ERROR_MAX_LENGTH) {
            msg = msg.substring(0, ERROR_MAX_LENGTH);
        }
        connection.setLastError(msg);
        connectionRepository.save(connection);
    }

    private MonthlyFlows resolveMonthlyFlows(
            Account account,
            ExternalAccountSyncData syncData,
            LocalDate monthStart,
            LocalDate monthEnd) {
        List<ExternalTransaction> rawTransactions = syncData.transactions();
        if (rawTransactions == null || rawTransactions.isEmpty()) {
            return new MonthlyFlows(syncData.monthlyInflow(), syncData.monthlyOutflow());
        }

        List<ExternalTransaction> deduplicated = deduplicateTransactions(rawTransactions, monthStart, monthEnd);
        List<ClassificationRule> rules = account.getHouseholdId() == null
                ? List.of()
                : classificationRuleRepository.findByHouseholdIdOrderByPriorityAsc(account.getHouseholdId());
        Set<String> householdOtherIbans = resolveHouseholdOtherIbans(account);

        BigDecimal inflow = BigDecimal.ZERO;
        BigDecimal outflow = BigDecimal.ZERO;
        for (ExternalTransaction tx : deduplicated) {
            if (tx.amount() == null || tx.amount().signum() == 0) {
                continue;
            }
            if (isInternalOrSavingsTransit(tx, rules, householdOtherIbans)) {
                continue;
            }
            if (tx.amount().signum() > 0) {
                inflow = inflow.add(tx.amount());
            } else {
                outflow = outflow.add(tx.amount().abs());
            }
        }
        return new MonthlyFlows(inflow, outflow);
    }

    /**
     * Two-stage deduplication:
     * <ol>
     * <li>Primary: unique {@code externalTransactionId} when provided,</li>
     * <li>Secondary: {@code bookingDate + amount + normalizedPurpose} to catch
     * duplicates without an external ID or overlapping CSV/provider feeds.</li>
     * </ol>
     */
    static List<ExternalTransaction> deduplicateTransactions(
            List<ExternalTransaction> transactions,
            LocalDate monthStart,
            LocalDate monthEnd) {
        Set<String> seenExternalIds = new HashSet<>();
        Set<String> seenFingerprints = new HashSet<>();
        List<ExternalTransaction> result = new ArrayList<>();

        for (ExternalTransaction tx : transactions) {
            if (tx == null || tx.amount() == null) {
                continue;
            }
            if (tx.bookingDate() != null
                    && (tx.bookingDate().isBefore(monthStart) || tx.bookingDate().isAfter(monthEnd))) {
                continue;
            }
            String extId = tx.externalTransactionId() == null ? null : tx.externalTransactionId().trim();
            String fingerprint = secondaryFingerprint(tx);
            if (extId != null && !extId.isEmpty()) {
                if (!seenExternalIds.add(extId)) {
                    continue;
                }
                seenFingerprints.add(fingerprint);
            } else if (!seenFingerprints.add(fingerprint)) {
                continue;
            }
            result.add(tx);
        }
        return result;
    }

    private static String secondaryFingerprint(ExternalTransaction tx) {
        String datePart = tx.bookingDate() == null ? "" : tx.bookingDate().toString();
        String amountPart = tx.amount().setScale(2, RoundingMode.HALF_UP).toPlainString();
        String purposePart = tx.purpose() == null
                ? ""
                : tx.purpose().trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return datePart + "|" + amountPart + "|" + purposePart;
    }

    private static boolean isInternalOrSavingsTransit(
            ExternalTransaction tx,
            List<ClassificationRule> rules,
            Set<String> householdOtherIbans) {
        for (ClassificationRule rule : rules) {
            if (matchesRule(rule, tx)) {
                FlowType flow = rule.getTargetFlow();
                return flow == FlowType.SAVINGS_TRANSIT || flow == FlowType.INTERNAL_TRANSFER;
            }
        }
        String counterpartyIban = OpenBankingHttpSupport.normalizeIban(tx.counterpartyIban());
        return counterpartyIban != null && householdOtherIbans.contains(counterpartyIban);
    }

    private static boolean matchesRule(ClassificationRule rule, ExternalTransaction tx) {
        boolean hasCondition = false;
        String ruleIban = OpenBankingHttpSupport.normalizeIban(rule.getMatchIban());
        if (ruleIban != null) {
            hasCondition = true;
            String txIban = OpenBankingHttpSupport.normalizeIban(tx.counterpartyIban());
            if (!ruleIban.equals(txIban)) {
                return false;
            }
        }
        if (rule.getMatchCounterpartyRegex() != null && !rule.getMatchCounterpartyRegex().isBlank()) {
            hasCondition = true;
            if (!matchesRegex(rule.getMatchCounterpartyRegex(), tx.counterpartyName())) {
                return false;
            }
        }
        if (rule.getMatchPurposeRegex() != null && !rule.getMatchPurposeRegex().isBlank()) {
            hasCondition = true;
            if (!matchesRegex(rule.getMatchPurposeRegex(), tx.purpose())) {
                return false;
            }
        }
        return hasCondition;
    }

    private static boolean matchesRegex(String regex, String value) {
        if (value == null) {
            return false;
        }
        try {
            return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(value).find();
        } catch (PatternSyntaxException ex) {
            return false;
        }
    }

    private Set<String> resolveHouseholdOtherIbans(Account currentAccount) {
        if (currentAccount.getHouseholdId() == null) {
            return Set.of();
        }
        Set<String> ibans = new HashSet<>();
        for (Account other : accountRepository.findByHouseholdIdAndActiveTrueOrderByAccountTypeAscNameAsc(
                currentAccount.getHouseholdId())) {
            if (other.getId().equals(currentAccount.getId())) {
                continue;
            }
            String normalized = OpenBankingHttpSupport.normalizeIban(other.getAccountNumber());
            if (normalized != null) {
                ibans.add(normalized);
            }
        }
        return ibans;
    }

    private void autoMatchAccounts(BankConnection connection, List<ExternalBankAccount> externalAccounts) {
        if (externalAccounts == null || externalAccounts.isEmpty() || connection.getHouseholdId() == null) {
            return;
        }
        List<Account> householdAccounts = accountRepository
                .findByHouseholdIdAndActiveTrueOrderByAccountTypeAscNameAsc(connection.getHouseholdId())
                .stream()
                .filter(a -> a.getAccountType() != AccountType.INSTITUTION)
                .toList();

        for (ExternalBankAccount ext : externalAccounts) {
            String extIban = OpenBankingHttpSupport.normalizeIban(ext.iban());
            if (extIban == null) {
                continue;
            }
            List<Account> matches = householdAccounts.stream()
                    .filter(a -> extIban.equals(OpenBankingHttpSupport.normalizeIban(a.getAccountNumber())))
                    .filter(a -> a.getBankConnectionId() == null || connection.getId().equals(a.getBankConnectionId()))
                    .toList();
            if (matches.size() == 1) {
                Account target = matches.get(0);
                target.setBankConnectionId(connection.getId());
                target.setExternalAccountId(ext.externalAccountId());
                accountRepository.save(target);
            }
        }
    }

    private void ensureConsentValid(BankConnection connection) {
        if (connection.isConsentExpired(OffsetDateTime.now())) {
            if (connection.getStatus() != BankConnectionStatus.EXPIRED) {
                connection.setStatus(BankConnectionStatus.EXPIRED);
                connectionRepository.save(connection);
            }
            throw new IllegalStateException("PSD2 consent has expired for " + connection.getInstitutionName());
        }
    }

    private boolean isDemoHousehold(UUID householdId) {
        if (householdService == null || householdId == null) {
            return false;
        }
        return householdService.findById(householdId).map(Household::isDemo).orElse(false);
    }

    private UUID resolveEffectiveHouseholdId(UUID requestedHouseholdId) {
        return householdService == null
                ? requestedHouseholdId
                : householdService.resolveEffectiveHouseholdId(requestedHouseholdId);
    }

    private UUID requireHouseholdId(UUID requestedHouseholdId) {
        UUID effectiveId = resolveEffectiveHouseholdId(requestedHouseholdId);
        if (effectiveId == null) {
            throw new IllegalArgumentException("No active household available");
        }
        return effectiveId;
    }

    private void requireEncryptionConfigured() {
        if (!sessionCryptoService.isConfigured()) {
            throw new IllegalStateException("MONEYPOLY_CRYPTO_KEY is not configured");
        }
    }

    private BankConnection requireConnection(UUID connectionId) {
        if (connectionId == null) {
            throw new IllegalArgumentException("Bank connection ID is required");
        }
        return connectionRepository.findById(connectionId)
                .orElseThrow(() -> new IllegalArgumentException("Bank connection not found: " + connectionId));
    }

    private Account requireAccount(UUID accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("Account ID is required");
        }
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));
    }

    private BankSyncProvider requireProvider(String providerId) {
        return findProvider(providerId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown Open-Banking provider: " + providerId));
    }

    private BankSyncProvider requireConfiguredProvider(String providerId) {
        BankSyncProvider provider = requireProvider(providerId);
        if (!provider.isConfigured()) {
            throw new IllegalStateException("Open-Banking provider is not configured: " + provider.displayName());
        }
        return provider;
    }

    private static Map<String, BankSyncProvider> indexProviders(List<BankSyncProvider> providers) {
        Map<String, BankSyncProvider> map = new LinkedHashMap<>();
        if (providers != null) {
            for (BankSyncProvider provider : providers) {
                if (provider != null && provider.providerId() != null) {
                    map.put(provider.providerId().trim().toUpperCase(Locale.ROOT), provider);
                }
            }
        }
        return map;
    }

    static String extractCodeOrTicket(String rawInput) {
        if (rawInput == null) {
            return null;
        }
        String trimmed = rawInput.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.contains("?")) {
            String query = null;
            try {
                query = URI.create(trimmed).getRawQuery();
            } catch (IllegalArgumentException ignored) {
                int idx = trimmed.indexOf('?');
                if (idx >= 0 && idx + 1 < trimmed.length()) {
                    query = trimmed.substring(idx + 1);
                }
            }
            if (query != null && !query.isBlank()) {
                for (String pair : query.split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                    if ("code".equalsIgnoreCase(key) || "ticket".equalsIgnoreCase(key)) {
                        String val = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8).trim();
                        if (!val.isEmpty()) {
                            return val;
                        }
                    }
                }
            }
            throw new IllegalArgumentException("Callback URL does not contain a code or ticket parameter");
        }
        return trimmed;
    }

    private record MonthlyFlows(BigDecimal inflow, BigDecimal outflow) {
    }
}
