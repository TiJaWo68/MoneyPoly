package de.in.mp.energy.sync;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.in.mp.energy.sync.EnergyDeviceModels.ChargingSession;
import de.in.mp.energy.sync.EnergyDeviceModels.DeviceSnapshot;

/**
 * Reads completed RFID-tagged charging sessions from a local Kathrein
 * easyOperate wallbox ({@code wallbox/login.php}, {@code wallbox/info.php}).
 */
@Component
public class KathreinWallboxClient {

    private static final BigDecimal WH_PER_KWH = new BigDecimal("1000");
    private static final int MAX_PAGES = 20;

    private final EnergyHttpHelper httpHelper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KathreinWallboxClient(EnergyHttpHelper httpHelper) {
        this.httpHelper = httpHelper;
    }

    public DeviceSnapshot fetch(EnergyDataSource source, String password) throws IOException, InterruptedException {
        String base = EnergyHttpHelper.normalizeBaseUrl(source.getBaseUrl());
        String account = source.getUsername() != null && !source.getUsername().isBlank()
                ? source.getUsername().trim()
                : "operator";
        if (password == null || password.isBlank()) {
            throw new IOException("Wallbox password is missing");
        }
        String loginUrl = base + "/wallbox/login.php?action=login&account="
                + URLEncoder.encode(account, StandardCharsets.UTF_8)
                + "&pass=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        String loginBody = httpHelper.get(loginUrl, source.isTrustSelfSigned());
        String token = parseLoginToken(loginBody);

        List<ChargingSession> allSessions = new ArrayList<>();
        int totalPages = 1;
        for (int page = 0; page < totalPages && page < MAX_PAGES; page++) {
            String statUrl = base + "/wallbox/info.php?action=get_remote_statistic&user="
                    + URLEncoder.encode(token, StandardCharsets.UTF_8)
                    + "&index=" + page;
            String statBody = httpHelper.get(statUrl, source.isTrustSelfSigned());
            PageResult parsed = parseStatisticPage(statBody);
            allSessions.addAll(parsed.sessions());
            totalPages = Math.max(1, parsed.totalPages());
        }

        Map<String, String> tagLabels = fetchPairingLabelsQuietly(base, token, source.isTrustSelfSigned());
        return new DeviceSnapshot(Map.of(), List.copyOf(allSessions), tagLabels);
    }

    String parseLoginToken(String json) throws IOException {
        JsonNode root = objectMapper.readTree(json);
        JsonNode login = root.path("login");
        if (!"ok".equalsIgnoreCase(login.path("status").asText())) {
            throw new IOException("Kathrein wallbox login failed");
        }
        String token = login.path("token").asText("");
        if (token.isBlank()) {
            throw new IOException("Kathrein wallbox returned empty token");
        }
        return token;
    }

    PageResult parseStatisticPage(String json) throws IOException {
        JsonNode root = objectMapper.readTree(json);
        if (!"ok".equalsIgnoreCase(root.path("status").asText())) {
            throw new IOException("Kathrein wallbox statistic request failed");
        }
        int totalPages = root.path("table_pages").asInt(1);
        List<ChargingSession> sessions = new ArrayList<>();
        JsonNode rows = root.path("tbl_remote_statistic");
        if (rows.isArray()) {
            for (JsonNode row : rows) {
                String status = row.path("status").asText("");
                if (!"loading_done".equalsIgnoreCase(status)) {
                    continue;
                }
                long remoteId = row.path("remoteId").asLong(-1);
                String userTag = row.path("user").asText("").trim();
                if (remoteId <= 0 || userTag.isBlank() || !row.hasNonNull("energy")) {
                    continue;
                }
                BigDecimal kwh = row.get("energy").decimalValue().divide(WH_PER_KWH, 3, RoundingMode.HALF_UP);
                sessions.add(new ChargingSession(remoteId, userTag, kwh));
            }
        }
        return new PageResult(totalPages, sessions);
    }

    private Map<String, String> fetchPairingLabelsQuietly(String base, String token, boolean trustSelfSigned) {
        try {
            String url = base + "/wallbox/pairing.php?action=get_pairings&user="
                    + URLEncoder.encode(token, StandardCharsets.UTF_8);
            String body = httpHelper.get(url, trustSelfSigned);
            JsonNode root = objectMapper.readTree(body);
            Map<String, String> labels = new LinkedHashMap<>();
            JsonNode list = root.path("tbl_pairing");
            if (list.isArray()) {
                for (JsonNode item : list) {
                    String tag = item.path("id").asText(item.path("rfid").asText("")).trim();
                    String name = item.path("name").asText(item.path("description").asText("")).trim();
                    if (!tag.isBlank() && !name.isBlank()) {
                        labels.put(tag, name);
                    }
                }
            }
            return labels;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    record PageResult(int totalPages, List<ChargingSession> sessions) {
    }
}
