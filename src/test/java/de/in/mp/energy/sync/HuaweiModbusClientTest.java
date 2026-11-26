package de.in.mp.energy.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

import de.in.mp.energy.sync.EnergyDeviceModels.DeviceSnapshot;
import de.in.mp.energy.sync.HuaweiModbusClient.HostPort;

class HuaweiModbusClientTest {

    private final HuaweiModbusClient client = new HuaweiModbusClient();

    @Test
    void parsesHostPortAndUnitIds() {
        assertThat(HuaweiModbusClient.parseHostPort("homeassistant.local:502"))
                .isEqualTo(new HostPort("homeassistant.local", 502));
        assertThat(HuaweiModbusClient.parseHostPort("tcp://192.168.178.131:1502/"))
                .isEqualTo(new HostPort("192.168.178.131", 1502));
        assertThat(HuaweiModbusClient.parseHostPort("homeassistant.local"))
                .isEqualTo(new HostPort("homeassistant.local", 502));

        assertThat(HuaweiModbusClient.parseUnitIds("1, 2, 16")).containsExactly(1, 2, 16);
        assertThat(HuaweiModbusClient.parseUnitIds("")).containsExactly(1);
        assertThat(HuaweiModbusClient.parseUnitIds(null)).containsExactly(1);
    }

    @Test
    void rejectsInvalidPortOrUnitId() {
        assertThatThrownBy(() -> HuaweiModbusClient.parseHostPort("host:70000"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HuaweiModbusClient.parseUnitIds("1, 300"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fetchesAndSumsMultipleInverterUnitsOverModbusTcp() throws Exception {
        Map<Integer, Long> unitRawValues = Map.of(
                1, 1823966L,
                2, 160L,
                16, 2058454L);

        try (ServerSocket server = new ServerSocket(0);
                ExecutorService pool = Executors.newSingleThreadExecutor()) {
            pool.submit(() -> {
                try (Socket conn = server.accept()) {
                    DataInputStream in = new DataInputStream(conn.getInputStream());
                    OutputStream out = conn.getOutputStream();
                    for (int i = 0; i < 3; i++) {
                        byte[] req = new byte[12];
                        in.readFully(req);
                        int txHi = req[0] & 0xFF;
                        int txLo = req[1] & 0xFF;
                        int unit = req[6] & 0xFF;
                        long val = unitRawValues.getOrDefault(unit, 0L);
                        byte[] resp = new byte[] {
                            (byte) txHi, (byte) txLo, 0, 0, 0, 7,
                            (byte) unit, 3, 4,
                            (byte) ((val >> 24) & 0xFF),
                            (byte) ((val >> 16) & 0xFF),
                            (byte) ((val >> 8) & 0xFF),
                            (byte) (val & 0xFF)
                        };
                        out.write(resp);
                        out.flush();
                    }
                } catch (IOException ignored) {
                }
            });

            EnergyDataSource source = new EnergyDataSource();
            source.setProvider(EnergyDataSourceProvider.HUAWEI_MODBUS);
            source.setBaseUrl("127.0.0.1:" + server.getLocalPort());
            source.setUsername("1,2,16");

            DeviceSnapshot snapshot = client.fetch(source);

            assertThat(snapshot.absoluteKwhByMetric().get(HuaweiModbusClient.METRIC_CUMULATIVE_ENERGY))
                    .isEqualByComparingTo(new BigDecimal("38825.800"));
            assertThat(snapshot.absoluteKwhByMetric().get("UNIT_1"))
                    .isEqualByComparingTo(new BigDecimal("18239.660"));
            assertThat(snapshot.absoluteKwhByMetric().get("UNIT_2"))
                    .isEqualByComparingTo(new BigDecimal("1.600"));
            assertThat(snapshot.absoluteKwhByMetric().get("UNIT_16"))
                    .isEqualByComparingTo(new BigDecimal("20584.540"));
        }
    }
}
