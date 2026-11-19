package de.in.mp.finance.banksync;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
 * {@link BankSyncProvider} backed by YAXI's interactive AIS widget/session API
 * (covering e.g. Trade Republic alongside standard PSD2 banks).
 * <p>
 * YAXI relies on interactive browser/TAN sessions and therefore reports
 * {@link #supportsBackgroundSync()} as {@code false}.
 */
@Component
public class YaxiBankSyncProvider implements BankSyncProvider {

    public static final String PROVIDER_ID = "YAXI";
    private static final String DEFAULT_BASE_URL = "https://api.yaxi.tech";
    private static final int DEFAULT_SESSION_HOURS = 1;

    private static final List<BankInstitution> DEFAULT_INSTITUTIONS = List.of(
            new BankInstitution("trade-republic-de", "Trade Republic", "TRBKDEBB", "DE"),
            new BankInstitution("c24-de", "C24 Bank", "C24BDEFF", "DE"),
            new BankInstitution("targobank-de", "Targobank", "CMCIDEDD", "DE"),
            new BankInstitution("ing-de", "ING", "INGDDEFF", "DE"),
            new BankInstitution("dkb-de", "DKB", "BYLADEM1001", "DE"));

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;

    @Autowired
    public YaxiBankSyncProvider(
            @Value("${moneypoly.banksync.yaxi.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl,
            @Value("${moneypoly.banksync.yaxi.api-key:${MONEYPOLY_YAXI_API_KEY:}}") String apiKey) {
        this(OpenBankingHttpSupport.defaultHttpClient(), baseUrl, apiKey);
    }

    YaxiBankSyncProvider(HttpClient httpClient, String baseUrl, String apiKey) {
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();
        this.baseUrl = OpenBankingHttpSupport.normalizeBaseUrl(baseUrl, DEFAULT_BASE_URL);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public String displayName() {
        return "YAXI (Interaktiv)";
    }

    @Override
    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    @Override
    public boolean supportsBackgroundSync() {
        return false;
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
                baseUrl + "/v1/ais/institutions?country=" + OpenBankingHttpSupport.encode(effectiveCountry),
                apiKeyHeaders(null));
        JsonNode items = root.path("institutions");
        if (!items.isArray() || items.isEmpty()) {
            return DEFAULT_INSTITUTIONS;
        }
        List<BankInstitution> result = new ArrayList<>();
        for (JsonNode item : items) {
            String id = OpenBankingHttpSupport.textOrNull(item, "id");
            String name = OpenBankingHttpSupport.textOrNull(item, "name");
            if (id == null || name == null) {
                continue;
            }
            String bic = OpenBankingHttpSupport.textOrNull(item, "bic");
            String itemCountry = OpenBankingHttpSupport.textOrNull(item, "country");
            result.add(new BankInstitution(
                    id,
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
        if (institution == null || institution.id() == null || institution.id().isBlank()) {
            throw new IllegalArgumentException("Institution is required");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("institutionId", institution.id());
        body.put("redirectUrl", callbackUrl);
        body.put("state", state);

        JsonNode response = OpenBankingHttpSupport.postJson(
                httpClient,
                objectMapper,
                baseUrl + "/v1/ais/sessions",
                body,
                apiKeyHeaders(null));
        String redirectUrl = OpenBankingHttpSupport.textOrNull(response, "redirectUrl");
        String sessionId = OpenBankingHttpSupport.textOrNull(response, "sessionId");
        if (redirectUrl == null || sessionId == null) {
            throw new IllegalStateException("YAXI did not return redirectUrl/sessionId");
        }
        OffsetDateTime expiresAt = OpenBankingHttpSupport.parseOffsetDateTime(
                OpenBankingHttpSupport.textOrNull(response, "expiresAt"),
                OffsetDateTime.now().plusHours(DEFAULT_SESSION_HOURS));
        String pendingPayload = writePayload(Map.of(
                "sessionId", sessionId,
                "state", state == null ? "" : state));
        return new AuthorizationInitiation(redirectUrl, state, pendingPayload, expiresAt);
    }

    @Override
    public AuthorizedSession completeAuthorization(String codeOrTicket, String pendingSessionPayload) {
        requireConfigured();
        if (codeOrTicket == null || codeOrTicket.isBlank()) {
            throw new IllegalArgumentException("YAXI ticket/code is required");
        }
        String sessionId = readField(pendingSessionPayload, "sessionId");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("ticket", codeOrTicket.trim());

        JsonNode response = OpenBankingHttpSupport.postJson(
                httpClient,
                objectMapper,
                baseUrl + "/v1/ais/sessions/complete",
                body,
                apiKeyHeaders(null));
        String sessionToken = OpenBankingHttpSupport.textOrNull(response, "sessionToken");
        if (sessionToken == null) {
            throw new IllegalStateException("YAXI did not return a sessionToken");
        }
        OffsetDateTime expiresAt = OpenBankingHttpSupport.parseOffsetDateTime(
                OpenBankingHttpSupport.textOrNull(response, "expiresAt"),
                OffsetDateTime.now().plusDays(90));
        List<ExternalBankAccount> accounts = parseAccounts(response.path("accounts"));
        return new AuthorizedSession(writePayload(Map.of("sessionToken", sessionToken)), expiresAt, accounts);
    }

    @Override
    public List<ExternalBankAccount> fetchAccounts(String decryptedSessionPayload) {
        requireConfigured();
        String sessionToken = readField(decryptedSessionPayload, "sessionToken");
        JsonNode response = OpenBankingHttpSupport.getJson(
                httpClient,
                objectMapper,
                baseUrl + "/v1/ais/accounts",
                apiKeyHeaders(sessionToken));
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
        String sessionToken = readField(decryptedSessionPayload, "sessionToken");
        LocalDate effectiveFrom = fromDate != null ? fromDate : LocalDate.now().withDayOfMonth(1);
        LocalDate effectiveTo = toDate != null ? toDate : LocalDate.now();
        String url = baseUrl + "/v1/ais/accounts/"
                + OpenBankingHttpSupport.encode(externalAccountId.trim())
                + "/sync?from=" + effectiveFrom + "&to=" + effectiveTo;
        JsonNode response = OpenBankingHttpSupport.getJson(
                httpClient, objectMapper, url, apiKeyHeaders(sessionToken));

        BigDecimal balance = OpenBankingHttpSupport.decimalOrNull(response.path("balance"));
        if (balance == null) {
            throw new IllegalStateException("YAXI sync response did not contain a numeric balance");
        }
        LocalDate asOfDate = OpenBankingHttpSupport.parseLocalDate(
                OpenBankingHttpSupport.textOrNull(response, "asOfDate"), effectiveTo);
        BigDecimal monthlyInflow = OpenBankingHttpSupport.decimalOrNull(response.path("monthlyInflow"));
        BigDecimal monthlyOutflow = OpenBankingHttpSupport.decimalOrNull(response.path("monthlyOutflow"));
        List<ExternalTransaction> transactions = parseTransactions(response.path("transactions"));

        return new ExternalAccountSyncData(
                externalAccountId.trim(),
                asOfDate,
                balance,
                monthlyInflow,
                monthlyOutflow,
                transactions);
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new IllegalStateException("YAXI provider API key is not configured");
        }
    }

    private Map<String, String> apiKeyHeaders(String bearerToken) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Api-Key", apiKey);
        if (bearerToken != null && !bearerToken.isBlank()) {
            headers.put("Authorization", "Bearer " + bearerToken);
        }
        return headers;
    }

    private String readField(String payload, String fieldName) {
        return OpenBankingHttpSupport.readJsonField(objectMapper, payload, fieldName, "YAXI");
    }

    private String writePayload(Map<String, String> data) {
        return OpenBankingHttpSupport.writeJsonPayload(objectMapper, data, "YAXI");
    }

    private static List<ExternalBankAccount> parseAccounts(JsonNode accountsNode) {
        if (accountsNode == null || !accountsNode.isArray()) {
            return List.of();
        }
        List<ExternalBankAccount> result = new ArrayList<>();
        for (JsonNode acc : accountsNode) {
            String id = OpenBankingHttpSupport.textOrNull(acc, "id");
            if (id == null) {
                continue;
            }
            String iban = OpenBankingHttpSupport.normalizeIban(OpenBankingHttpSupport.textOrNull(acc, "iban"));
            String name = OpenBankingHttpSupport.textOrNull(acc, "name");
            String owner = OpenBankingHttpSupport.textOrNull(acc, "ownerName");
            String currency = OpenBankingHttpSupport.textOrNull(acc, "currency");
            String rawType = OpenBankingHttpSupport.textOrNull(acc, "type");
            result.add(new ExternalBankAccount(
                    id,
                    iban,
                    name == null ? id : name,
                    owner,
                    currency == null ? Account.DEFAULT_CURRENCY : currency.toUpperCase(Locale.ROOT),
                    OpenBankingHttpSupport.suggestAccountType(name, rawType)));
        }
        return result;
    }

    private static List<ExternalTransaction> parseTransactions(JsonNode txNode) {
        if (txNode == null || !txNode.isArray()) {
            return List.of();
        }
        List<ExternalTransaction> result = new ArrayList<>();
        for (JsonNode tx : txNode) {
            BigDecimal amount = OpenBankingHttpSupport.decimalOrNull(tx.path("amount"));
            if (amount == null) {
                continue;
            }
            String id = OpenBankingHttpSupport.textOrNull(tx, "id");
            LocalDate bookingDate = OpenBankingHttpSupport.parseLocalDate(
                    OpenBankingHttpSupport.textOrNull(tx, "bookingDate"), LocalDate.now());
            String counterpartyName = OpenBankingHttpSupport.textOrNull(tx, "counterpartyName");
            String counterpartyIban = OpenBankingHttpSupport.normalizeIban(
                    OpenBankingHttpSupport.textOrNull(tx, "counterpartyIban"));
            String purpose = OpenBankingHttpSupport.textOrNull(tx, "purpose");
            result.add(new ExternalTransaction(
                    id,
                    bookingDate,
                    amount,
                    counterpartyName,
                    counterpartyIban,
                    purpose == null ? "" : purpose));
        }
        return result;
    }
}
