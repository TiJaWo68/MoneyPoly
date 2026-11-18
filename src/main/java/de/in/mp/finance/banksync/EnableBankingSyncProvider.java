package de.in.mp.finance.banksync;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.in.mp.finance.Account;

/**
 * {@link BankSyncProvider} backed by the Enable Banking PSD2 AIS API.
 * <p>
 * Supports long-lived consents (90–180 days) and unattended background
 * synchronization via {@code @Scheduled}.
 */
@Component
public class EnableBankingSyncProvider implements BankSyncProvider {

    public static final String PROVIDER_ID = "ENABLE_BANKING";
    private static final String DEFAULT_BASE_URL = "https://api.enablebanking.com";
    private static final int DEFAULT_CONSENT_DAYS = 180;

    private static final List<BankInstitution> DEFAULT_INSTITUTIONS = List.of(
            new BankInstitution("C24 Bank", "C24 Bank", "C24BDEFF", "DE"),
            new BankInstitution("Targobank", "Targobank", "CMCIDEDD", "DE"),
            new BankInstitution("Trade Republic", "Trade Republic", "TRBKDEBB", "DE"),
            new BankInstitution("DKB", "DKB", "BYLADEM1001", "DE"),
            new BankInstitution("ING", "ING", "INGDDEFF", "DE"),
            new BankInstitution("Scalable Capital", "Scalable Capital", "", "DE"));

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String appId;
    private final String token;
    private final PrivateKey privateKey;
    private final int consentDays;

    @Autowired
    public EnableBankingSyncProvider(
            @Value("${moneypoly.banksync.enable-banking.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl,
            @Value("${moneypoly.banksync.enable-banking.app-id:${MONEYPOLY_ENABLE_BANKING_APP_ID:}}") String appId,
            @Value("${moneypoly.banksync.enable-banking.token:${MONEYPOLY_ENABLE_BANKING_TOKEN:}}") String token,
            @Value("${moneypoly.banksync.enable-banking.private-key:${MONEYPOLY_ENABLE_BANKING_PRIVATE_KEY:}}") String privateKeyPem,
            @Value("${moneypoly.banksync.enable-banking.private-key-path:${MONEYPOLY_ENABLE_BANKING_PRIVATE_KEY_PATH:}}") String privateKeyPath,
            @Value("${moneypoly.banksync.enable-banking.consent-days:" + DEFAULT_CONSENT_DAYS + "}") int consentDays) {
        this(OpenBankingHttpSupport.defaultHttpClient(), baseUrl, appId, token,
                loadPrivateKey(privateKeyPem, privateKeyPath), consentDays);
    }

    EnableBankingSyncProvider(
            HttpClient httpClient,
            String baseUrl,
            String appId,
            String token,
            int consentDays) {
        this(httpClient, baseUrl, appId, token, null, consentDays);
    }

    EnableBankingSyncProvider(
            HttpClient httpClient,
            String baseUrl,
            String appId,
            String token,
            PrivateKey privateKey,
            int consentDays) {
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();
        this.baseUrl = OpenBankingHttpSupport.normalizeBaseUrl(baseUrl, DEFAULT_BASE_URL);
        this.appId = appId == null ? "" : appId.trim();
        this.token = token == null ? "" : token.trim();
        this.privateKey = privateKey;
        this.consentDays = consentDays > 0 ? consentDays : DEFAULT_CONSENT_DAYS;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public String displayName() {
        return "Enable Banking";
    }

    @Override
    public boolean isConfigured() {
        return !appId.isBlank() && (privateKey != null || !token.isBlank());
    }

    @Override
    public boolean supportsBackgroundSync() {
        return true;
    }

    @Override
    public List<BankInstitution> listInstitutions(String country) {
        if (!isConfigured()) {
            return DEFAULT_INSTITUTIONS;
        }
        String effectiveCountry = (country == null || country.isBlank()) ? "DE" : country.trim().toUpperCase(Locale.ROOT);
        JsonNode root = OpenBankingHttpSupport.getJson(
                httpClient,
                objectMapper,
                baseUrl + "/aspsps?country=" + OpenBankingHttpSupport.encode(effectiveCountry),
                authHeaders());
        JsonNode aspsps = root.path("aspsps");
        if (!aspsps.isArray() || aspsps.isEmpty()) {
            return DEFAULT_INSTITUTIONS;
        }
        List<BankInstitution> result = new ArrayList<>();
        for (JsonNode item : aspsps) {
            String name = OpenBankingHttpSupport.textOrNull(item, "name");
            if (name == null) {
                continue;
            }
            String id = OpenBankingHttpSupport.textOrNull(item, "id");
            String bic = OpenBankingHttpSupport.textOrNull(item, "bic");
            String itemCountry = OpenBankingHttpSupport.textOrNull(item, "country");
            result.add(new BankInstitution(
                    id != null ? id : name,
                    name,
                    bic == null ? "" : bic,
                    itemCountry != null ? itemCountry : effectiveCountry));
        }
        return result.isEmpty() ? DEFAULT_INSTITUTIONS : result;
    }

    @Override
    public AuthorizationInitiation initiateAuthorization(
            BankInstitution institution,
            String callbackUrl,
            String state) {
        requireConfigured();
        if (institution == null || institution.name() == null || institution.name().isBlank()) {
            throw new IllegalArgumentException("Institution is required");
        }
        OffsetDateTime validUntil = OffsetDateTime.now().plusDays(consentDays);
        Map<String, Object> aspsp = new LinkedHashMap<>();
        aspsp.put("name", institution.name());
        aspsp.put("country", institution.country() != null && !institution.country().isBlank()
                ? institution.country()
                : "DE");

        Map<String, Object> access = new LinkedHashMap<>();
        access.put("valid_until", validUntil.toString());

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("aspsp", aspsp);
        requestBody.put("redirect_url", callbackUrl);
        requestBody.put("state", state);
        requestBody.put("access", access);
        requestBody.put("psu_type", "personal");

        JsonNode response = OpenBankingHttpSupport.postJson(
                httpClient, objectMapper, baseUrl + "/auth", requestBody, authHeaders());
        String redirectUrl = OpenBankingHttpSupport.textOrNull(response, "url");
        if (redirectUrl == null) {
            throw new IllegalStateException("Enable Banking did not return an authorization redirect URL");
        }
        String authorizationId = OpenBankingHttpSupport.textOrNull(response, "authorization_id");
        String pendingPayload = writePayload(Map.of(
                "authorization_id", authorizationId == null ? "" : authorizationId,
                "institution_id", institution.id() == null ? institution.name() : institution.id(),
                "institution_name", institution.name()));
        return new AuthorizationInitiation(redirectUrl, state, pendingPayload, validUntil);
    }

    @Override
    public AuthorizedSession completeAuthorization(String codeOrTicket, String pendingSessionPayload) {
        requireConfigured();
        if (codeOrTicket == null || codeOrTicket.isBlank()) {
            throw new IllegalArgumentException("Authorization code is required");
        }
        JsonNode response = OpenBankingHttpSupport.postJson(
                httpClient,
                objectMapper,
                baseUrl + "/sessions",
                Map.of("code", codeOrTicket.trim()),
                authHeaders());
        String sessionId = OpenBankingHttpSupport.textOrNull(response, "session_id");
        if (sessionId == null) {
            throw new IllegalStateException("Enable Banking did not return a session_id");
        }
        OffsetDateTime expiresAt = OpenBankingHttpSupport.parseOffsetDateTime(
                OpenBankingHttpSupport.textOrNull(response.path("access"), "valid_until"),
                OffsetDateTime.now().plusDays(consentDays));
        List<ExternalBankAccount> accounts = parseAccounts(response.path("accounts"));
        String storedPayload = writePayload(Map.of("session_id", sessionId));
        return new AuthorizedSession(storedPayload, expiresAt, accounts);
    }

    @Override
    public List<ExternalBankAccount> fetchAccounts(String decryptedSessionPayload) {
        requireConfigured();
        String sessionId = readSessionId(decryptedSessionPayload);
        JsonNode response = OpenBankingHttpSupport.getJson(
                httpClient,
                objectMapper,
                baseUrl + "/sessions/" + OpenBankingHttpSupport.encode(sessionId),
                authHeaders());
        return parseAccounts(response.path("accounts"));
    }

    @Override
    public ExternalAccountSyncData fetchAccountSyncData(
            String decryptedSessionPayload,
            String externalAccountId,
            LocalDate fromDate,
            LocalDate toDate) {
        requireConfigured();
        if (externalAccountId == null || externalAccountId.isBlank()) {
            throw new IllegalArgumentException("External account ID is required");
        }
        readSessionId(decryptedSessionPayload);
        String encodedAccountId = OpenBankingHttpSupport.encode(externalAccountId.trim());

        JsonNode balancesRoot = OpenBankingHttpSupport.getJson(
                httpClient,
                objectMapper,
                baseUrl + "/accounts/" + encodedAccountId + "/balances",
                authHeaders());
        BalanceSnapshot parsedBalance = extractBalance(balancesRoot.path("balances"), toDate);

        LocalDate effectiveFrom = fromDate != null ? fromDate : toDate.withDayOfMonth(1);
        LocalDate effectiveTo = toDate != null ? toDate : LocalDate.now();
        String txUrl = baseUrl + "/accounts/" + encodedAccountId
                + "/transactions?date_from=" + effectiveFrom + "&date_to=" + effectiveTo;
        JsonNode txRoot = OpenBankingHttpSupport.getJson(httpClient, objectMapper, txUrl, authHeaders());
        List<ExternalTransaction> transactions = parseTransactions(txRoot.path("transactions"));

        return new ExternalAccountSyncData(
                externalAccountId.trim(),
                parsedBalance.asOfDate(),
                parsedBalance.balance(),
                null,
                null,
                transactions);
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new IllegalStateException("Enable Banking provider credentials are not configured");
        }
    }

    private Map<String, String> authHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        String bearer = privateKey != null ? createSignedJwt() : token;
        headers.put("Authorization", "Bearer " + bearer);
        return headers;
    }

    String createSignedJwt() {
        if (privateKey == null) {
            throw new IllegalStateException("Enable Banking RSA private key is not configured");
        }
        try {
            long now = Instant.now().getEpochSecond();
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("typ", "JWT");
            header.put("alg", "RS256");
            header.put("kid", appId);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("iss", "enablebanking.com");
            payload.put("aud", "api.enablebanking.com");
            payload.put("iat", now);
            payload.put("exp", now + 3600);

            Base64.Encoder urlEncoder = Base64.getUrlEncoder().withoutPadding();
            String encodedHeader = urlEncoder.encodeToString(objectMapper.writeValueAsBytes(header));
            String encodedPayload = urlEncoder.encodeToString(objectMapper.writeValueAsBytes(payload));
            String signingInput = encodedHeader + "." + encodedPayload;

            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            String encodedSignature = urlEncoder.encodeToString(signature.sign());
            return signingInput + "." + encodedSignature;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign Enable Banking JWT", ex);
        }
    }

    static PrivateKey loadPrivateKey(String inlinePem, String keyPath) {
        String pem = inlinePem;
        if ((pem == null || pem.isBlank()) && keyPath != null && !keyPath.isBlank()) {
            try {
                pem = Files.readString(Path.of(keyPath.trim()), StandardCharsets.UTF_8);
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to read Enable Banking private key from " + keyPath, ex);
            }
        }
        if (pem == null || pem.isBlank()) {
            return null;
        }
        String sanitized = pem.replace("\\n", "\n")
                .replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s+", "");
        try {
            byte[] derBytes = Base64.getDecoder().decode(sanitized);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(derBytes));
        } catch (IllegalArgumentException | GeneralSecurityException ex) {
            throw new IllegalStateException("Invalid Enable Banking RSA PKCS#8 private key PEM", ex);
        }
    }

    private String readSessionId(String payload) {
        return OpenBankingHttpSupport.readJsonField(objectMapper, payload, "session_id", "Enable Banking");
    }

    private String writePayload(Map<String, String> data) {
        return OpenBankingHttpSupport.writeJsonPayload(objectMapper, data, "Enable Banking");
    }

    private static List<ExternalBankAccount> parseAccounts(JsonNode accountsNode) {
        if (accountsNode == null || !accountsNode.isArray()) {
            return List.of();
        }
        List<ExternalBankAccount> result = new ArrayList<>();
        for (JsonNode acc : accountsNode) {
            String uid = OpenBankingHttpSupport.textOrNull(acc, "uid");
            if (uid == null) {
                uid = OpenBankingHttpSupport.textOrNull(acc, "id");
            }
            if (uid == null) {
                continue;
            }
            String iban = OpenBankingHttpSupport.normalizeIban(
                    OpenBankingHttpSupport.textOrNull(acc.path("account_id"), "iban"));
            if (iban == null) {
                iban = OpenBankingHttpSupport.normalizeIban(OpenBankingHttpSupport.textOrNull(acc, "iban"));
            }
            String name = OpenBankingHttpSupport.textOrNull(acc, "name");
            if (name == null) {
                name = OpenBankingHttpSupport.textOrNull(acc, "product");
            }
            String owner = OpenBankingHttpSupport.textOrNull(acc, "owner_name");
            String currency = OpenBankingHttpSupport.textOrNull(acc, "currency");
            String cashType = OpenBankingHttpSupport.textOrNull(acc, "cash_account_type");
            result.add(new ExternalBankAccount(
                    uid,
                    iban,
                    name == null ? uid : name,
                    owner,
                    currency == null ? Account.DEFAULT_CURRENCY : currency.toUpperCase(Locale.ROOT),
                    OpenBankingHttpSupport.suggestAccountType(name, cashType)));
        }
        return result;
    }

    private static BalanceSnapshot extractBalance(JsonNode balancesNode, LocalDate fallbackDate) {
        LocalDate effectiveFallback = fallbackDate != null ? fallbackDate : LocalDate.now();
        if (balancesNode == null || !balancesNode.isArray() || balancesNode.isEmpty()) {
            throw new IllegalStateException("Enable Banking returned no balance entries");
        }
        JsonNode selected = balancesNode.get(0);
        for (JsonNode candidate : balancesNode) {
            String type = OpenBankingHttpSupport.textOrNull(candidate, "balance_type");
            if ("CLAV".equalsIgnoreCase(type) || "XPCD".equalsIgnoreCase(type) || "ITAV".equalsIgnoreCase(type)) {
                selected = candidate;
                break;
            }
        }
        BigDecimal balance = OpenBankingHttpSupport.decimalOrNull(selected.path("balance_amount").path("amount"));
        if (balance == null) {
            throw new IllegalStateException("Enable Banking balance entry did not contain a numeric amount");
        }
        LocalDate refDate = OpenBankingHttpSupport.parseLocalDate(
                OpenBankingHttpSupport.textOrNull(selected, "reference_date"), effectiveFallback);
        return new BalanceSnapshot(refDate, balance);
    }

    private static List<ExternalTransaction> parseTransactions(JsonNode txArray) {
        if (txArray == null || !txArray.isArray()) {
            return List.of();
        }
        List<ExternalTransaction> result = new ArrayList<>();
        for (JsonNode tx : txArray) {
            BigDecimal amount = OpenBankingHttpSupport.decimalOrNull(tx.path("transaction_amount").path("amount"));
            if (amount == null) {
                continue;
            }
            String indicator = OpenBankingHttpSupport.textOrNull(tx, "credit_debit_indicator");
            if ("DBIT".equalsIgnoreCase(indicator) && amount.signum() > 0) {
                amount = amount.negate();
            } else if ("CRDT".equalsIgnoreCase(indicator) && amount.signum() < 0) {
                amount = amount.abs();
            }
            String extId = OpenBankingHttpSupport.textOrNull(tx, "entry_reference");
            if (extId == null) {
                extId = OpenBankingHttpSupport.textOrNull(tx, "transaction_id");
            }
            LocalDate bookingDate = OpenBankingHttpSupport.parseLocalDate(
                    OpenBankingHttpSupport.textOrNull(tx, "booking_date"), LocalDate.now());
            boolean isCredit = amount.signum() >= 0;
            JsonNode partyNode = isCredit ? tx.path("debtor") : tx.path("creditor");
            JsonNode partyAccountNode = isCredit ? tx.path("debtor_account") : tx.path("creditor_account");
            String counterpartyName = OpenBankingHttpSupport.textOrNull(partyNode, "name");
            String counterpartyIban = OpenBankingHttpSupport.normalizeIban(
                    OpenBankingHttpSupport.textOrNull(partyAccountNode, "iban"));
            String purpose = joinRemittance(tx.path("remittance_information"));
            result.add(new ExternalTransaction(
                    extId, bookingDate, amount, counterpartyName, counterpartyIban, purpose));
        }
        return result;
    }

    private static String joinRemittance(JsonNode remittanceNode) {
        if (remittanceNode == null || remittanceNode.isNull()) {
            return "";
        }
        if (remittanceNode.isArray()) {
            List<String> lines = new ArrayList<>();
            for (JsonNode line : remittanceNode) {
                String txt = line.asText();
                if (txt != null && !txt.isBlank()) {
                    lines.add(txt.trim());
                }
            }
            return String.join(" ", lines);
        }
        String text = remittanceNode.asText();
        return text == null ? "" : text.trim();
    }

    private record BalanceSnapshot(LocalDate asOfDate, BigDecimal balance) {
    }
}
