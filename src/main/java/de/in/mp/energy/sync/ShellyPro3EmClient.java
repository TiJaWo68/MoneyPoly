package de.in.mp.energy.sync;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.in.mp.energy.sync.EnergyDeviceModels.DeviceSnapshot;

/**
 * Reads total active imported ({@code TOTAL_ACT}) and exported
 * ({@code TOTAL_ACT_RET}) energy from a Shelly Pro 3EM via
 * {@code /rpc/EMData.GetStatus?id=0}.
 */
@Component
public class ShellyPro3EmClient {

    public static final String METRIC_TOTAL_ACT = "TOTAL_ACT";
    public static final String METRIC_TOTAL_ACT_RET = "TOTAL_ACT_RET";
    private static final BigDecimal WH_PER_KWH = new BigDecimal("1000");

    private final EnergyHttpHelper httpHelper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ShellyPro3EmClient(EnergyHttpHelper httpHelper) {
        this.httpHelper = httpHelper;
    }

    public DeviceSnapshot fetch(EnergyDataSource source) throws IOException, InterruptedException {
        String url = EnergyHttpHelper.normalizeBaseUrl(source.getBaseUrl()) + "/rpc/EMData.GetStatus?id=0";
        String body = httpHelper.get(url, source.isTrustSelfSigned());
        return parseResponse(body);
    }

    DeviceSnapshot parseResponse(String json) throws IOException {
        JsonNode root = objectMapper.readTree(json);
        Map<String, BigDecimal> metrics = new LinkedHashMap<>();
        if (root.hasNonNull("total_act")) {
            metrics.put(METRIC_TOTAL_ACT, whToKwh(root.get("total_act").decimalValue()));
        }
        if (root.hasNonNull("total_act_ret")) {
            metrics.put(METRIC_TOTAL_ACT_RET, whToKwh(root.get("total_act_ret").decimalValue()));
        }
        if (metrics.isEmpty()) {
            throw new IOException("Shelly response missing total_act / total_act_ret");
        }
        return DeviceSnapshot.ofMetrics(metrics);
    }

    private static BigDecimal whToKwh(BigDecimal wh) {
        return wh.divide(WH_PER_KWH, 3, RoundingMode.HALF_UP);
    }
}
