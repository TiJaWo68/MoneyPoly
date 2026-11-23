package de.in.mp.energy.sync;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.in.mp.energy.sync.EnergyDeviceModels.DeviceSnapshot;

/**
 * Reads cumulative PV yield ({@code CUMULATIVE_ENERGY}) from a public Huawei
 * FusionSolar Kiosk endpoint ({@code /rest/pvms/web/kiosk/v1/station-kiosk-file}).
 */
@Component
public class FusionSolarKioskClient {

    public static final String METRIC_CUMULATIVE_ENERGY = "CUMULATIVE_ENERGY";

    private final EnergyHttpHelper httpHelper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FusionSolarKioskClient(EnergyHttpHelper httpHelper) {
        this.httpHelper = httpHelper;
    }

    public DeviceSnapshot fetch(EnergyDataSource source, String kioskKey) throws IOException, InterruptedException {
        String cleanKey = extractKioskKey(kioskKey);
        if (cleanKey.isBlank()) {
            throw new IOException("FusionSolar Kiosk key (kk) is missing");
        }
        String base = EnergyHttpHelper.normalizeBaseUrl(source.getBaseUrl());
        String url = base + "/rest/pvms/web/kiosk/v1/station-kiosk-file?kk="
                + URLEncoder.encode(cleanKey, StandardCharsets.UTF_8);
        String body = httpHelper.get(url, source.isTrustSelfSigned());
        return parseResponse(body);
    }

    DeviceSnapshot parseResponse(String json) throws IOException {
        JsonNode root = objectMapper.readTree(json);
        if (!root.path("success").asBoolean(false)) {
            throw new IOException("FusionSolar Kiosk returned failCode=" + root.path("failCode").asText("?"));
        }
        String rawData = root.path("data").asText("");
        if (rawData.isBlank()) {
            throw new IOException("FusionSolar Kiosk response has empty data field");
        }
        String unescaped = HtmlUtils.htmlUnescape(rawData);
        JsonNode dataNode = objectMapper.readTree(unescaped);
        JsonNode kpi = dataNode.path("realKpi");
        if (!kpi.hasNonNull("cumulativeEnergy")) {
            throw new IOException("FusionSolar Kiosk response missing realKpi.cumulativeEnergy");
        }
        BigDecimal cumulative = kpi.get("cumulativeEnergy").decimalValue().setScale(3, RoundingMode.HALF_UP);
        return DeviceSnapshot.ofMetrics(Map.of(METRIC_CUMULATIVE_ENERGY, cumulative));
    }

    static String extractKioskKey(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        int idx = trimmed.indexOf("kk=");
        if (idx >= 0) {
            String after = trimmed.substring(idx + 3);
            int amp = after.indexOf('&');
            return amp >= 0 ? after.substring(0, amp) : after;
        }
        return trimmed;
    }
}
