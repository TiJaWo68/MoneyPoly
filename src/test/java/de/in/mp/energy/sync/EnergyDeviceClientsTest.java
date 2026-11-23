package de.in.mp.energy.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import de.in.mp.energy.sync.EnergyDeviceModels.DeviceSnapshot;

class EnergyDeviceClientsTest {

    private final EnergyHttpHelper httpHelper = new EnergyHttpHelper();
    private final ShellyPro3EmClient shellyClient = new ShellyPro3EmClient(httpHelper);
    private final FusionSolarKioskClient kioskClient = new FusionSolarKioskClient(httpHelper);
    private final KathreinWallboxClient wallboxClient = new KathreinWallboxClient(httpHelper);

    @Test
    void parsesShellyPro3EmJsonIntoKwhMetrics() throws IOException {
        String json = """
                {
                  "id": 0,
                  "total_act": 8705850.32,
                  "total_act_ret": 19524700.10
                }
                """;
        DeviceSnapshot snapshot = shellyClient.parseResponse(json);

        assertThat(snapshot.absoluteKwhByMetric().get(ShellyPro3EmClient.METRIC_TOTAL_ACT))
                .isEqualByComparingTo("8705.850");
        assertThat(snapshot.absoluteKwhByMetric().get(ShellyPro3EmClient.METRIC_TOTAL_ACT_RET))
                .isEqualByComparingTo("19524.700");
    }

    @Test
    void parsesFusionSolarKioskJsonAndExtractsKeyFromFullUrl() throws IOException {
        assertThat(FusionSolarKioskClient.extractKioskKey(
                "https://uni003eu5.fusionsolar.huawei.com/pvmswebsite/nologin/assets/build/cloud.html#/kiosk?kk=abc123&lang=de"))
                .isEqualTo("abc123");

        String json = """
                {
                  "data": "{&quot;realKpi&quot;:{&quot;cumulativeEnergy&quot;:38896.4,&quot;dailyEnergy&quot;:5.56}}",
                  "success": true,
                  "failCode": 0
                }
                """;
        DeviceSnapshot snapshot = kioskClient.parseResponse(json);

        assertThat(snapshot.absoluteKwhByMetric().get(FusionSolarKioskClient.METRIC_CUMULATIVE_ENERGY))
                .isEqualByComparingTo("38896.400");
    }

    @Test
    void parsesKathreinWallboxLoginAndStatisticPages() throws IOException {
        String loginJson = """
                {"login":{"status":"ok","token":"tok-123"}}
                """;
        assertThat(wallboxClient.parseLoginToken(loginJson)).isEqualTo("tok-123");

        assertThatThrownBy(() -> wallboxClient.parseLoginToken("{\"login\":{\"status\":\"failed\"}}"))
                .isInstanceOf(IOException.class);

        String statJson = """
                {
                  "status": "ok",
                  "table_pages": "2",
                  "tbl_remote_statistic": [
                    {"remoteId": 398, "user": "9333A680", "status": "loading_done", "energy": 23376},
                    {"remoteId": 399, "user": "9333A680", "status": "charging", "energy": 1000},
                    {"remoteId": 397, "user": "04968ADA551390", "status": "loading_done", "energy": 23570}
                  ]
                }
                """;
        KathreinWallboxClient.PageResult page = wallboxClient.parseStatisticPage(statJson);

        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.sessions()).hasSize(2);
        assertThat(page.sessions().get(0).remoteId()).isEqualTo(398L);
        assertThat(page.sessions().get(0).userTag()).isEqualTo("9333A680");
        assertThat(page.sessions().get(0).energyKwh()).isEqualByComparingTo("23.376");
        assertThat(page.sessions().get(1).energyKwh()).isEqualByComparingTo("23.570");
    }
}
