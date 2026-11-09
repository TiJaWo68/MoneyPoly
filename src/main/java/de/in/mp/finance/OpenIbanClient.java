package de.in.mp.finance;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Lightweight HTTP client for the OpenIBAN validation endpoint. Resolves
 * current bank master data (name, BIC, ZIP, city) for a given IBAN.
 * <p>
 * Failures (offline, timeout, unexpected payload) are swallowed and reported as
 * an empty result so that the editor stays usable without an internet
 * connection.
 */
@Component
public class OpenIbanClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public OpenIbanClient(ObjectMapper objectMapper,
            @Value("${moneypoly.openiban.base-url:https://openiban.com}") String baseUrl) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public Optional<BankInfo> lookup(String iban) {
        if (iban == null || iban.isBlank()) {
            return Optional.empty();
        }

        String url = baseUrl + "/validate/" + iban.trim() + "?getBIC=true&validateBankCode=true";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            return mapResponse(objectMapper.readValue(response.body(), OpenIbanResponse.class));
        } catch (IOException exception) {
            return Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    Optional<BankInfo> mapResponse(OpenIbanResponse response) {
        if (response == null || !response.valid() || response.bankData() == null) {
            return Optional.empty();
        }
        return Optional.of(response.bankData());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OpenIbanResponse(boolean valid, BankInfo bankData) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BankInfo(String bankCode, String name, String zip, String city, String bic) {
    }
}
