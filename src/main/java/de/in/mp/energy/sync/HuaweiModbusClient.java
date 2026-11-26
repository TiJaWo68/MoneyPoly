package de.in.mp.energy.sync;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import de.in.mp.energy.sync.EnergyDeviceModels.DeviceSnapshot;

/**
 * Reads cumulative PV yield ({@code CUMULATIVE_ENERGY}) from one or more
 * Huawei SUN2000 inverters over Modbus-TCP (directly or via a Modbus proxy
 * such as Home Assistant's modbus-proxy add-on).
 * <p>
 * Register {@code 32106} holds the accumulated energy yield as an unsigned
 * 32-bit big-endian integer scaled by {@code 0.01 kWh}. Multiple comma-separated
 * Modbus unit IDs (e.g. {@code 1,2,16}) are queried sequentially on a single
 * TCP connection and summed into {@code CUMULATIVE_ENERGY}.
 */
@Component
public class HuaweiModbusClient {

    public static final String METRIC_CUMULATIVE_ENERGY = "CUMULATIVE_ENERGY";
    public static final int DEFAULT_PORT = 502;
    public static final int REGISTER_ACCUMULATED_YIELD = 32106;

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;
    private static final int FUNCTION_READ_HOLDING_REGISTERS = 0x03;
    private static final BigDecimal SCALE_HUNDREDTHS_KWH = new BigDecimal("100");

    public record HostPort(String host, int port) {
    }

    public DeviceSnapshot fetch(EnergyDataSource source) throws IOException {
        HostPort target = parseHostPort(source.getBaseUrl());
        List<Integer> unitIds = parseUnitIds(source.getUsername());

        Map<String, BigDecimal> metrics = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(target.host(), target.port()), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            int txId = 1;
            for (int unitId : unitIds) {
                BigDecimal unitKwh = readAccumulatedYieldKwh(in, out, txId++, unitId);
                metrics.put("UNIT_" + unitId, unitKwh);
                total = total.add(unitKwh);
            }
        }

        Map<String, BigDecimal> ordered = new LinkedHashMap<>();
        ordered.put(METRIC_CUMULATIVE_ENERGY, total.setScale(3, RoundingMode.HALF_UP));
        ordered.putAll(metrics);
        return DeviceSnapshot.ofMetrics(ordered);
    }

    static BigDecimal readAccumulatedYieldKwh(
            DataInputStream in, OutputStream out, int transactionId, int unitId) throws IOException {
        byte[] request = new byte[12];
        request[0] = (byte) ((transactionId >> 8) & 0xFF);
        request[1] = (byte) (transactionId & 0xFF);
        // Protocol ID = 0x0000, Length = 6 bytes remaining
        request[5] = 6;
        request[6] = (byte) (unitId & 0xFF);
        request[7] = FUNCTION_READ_HOLDING_REGISTERS;
        request[8] = (byte) ((REGISTER_ACCUMULATED_YIELD >> 8) & 0xFF);
        request[9] = (byte) (REGISTER_ACCUMULATED_YIELD & 0xFF);
        request[10] = 0x00;
        request[11] = 0x02;

        out.write(request);
        out.flush();

        byte[] mbap = new byte[7];
        try {
            in.readFully(mbap);
        } catch (EOFException eof) {
            throw new IOException("Modbus connection closed by peer for unit " + unitId, eof);
        }

        int respTx = ((mbap[0] & 0xFF) << 8) | (mbap[1] & 0xFF);
        int length = ((mbap[4] & 0xFF) << 8) | (mbap[5] & 0xFF);
        if (respTx != (transactionId & 0xFFFF)) {
            throw new IOException("Modbus transaction mismatch: expected " + transactionId + " got " + respTx);
        }
        if (length < 3 || length > 256) {
            throw new IOException("Invalid Modbus response length: " + length);
        }

        byte[] pdu = new byte[length - 1];
        in.readFully(pdu);

        int fn = pdu[0] & 0xFF;
        if ((fn & 0x80) != 0) {
            int code = pdu.length > 1 ? (pdu[1] & 0xFF) : 0;
            throw new IOException("Modbus exception for unit " + unitId + ": code 0x"
                    + Integer.toHexString(code));
        }
        if (fn != FUNCTION_READ_HOLDING_REGISTERS || pdu.length < 6 || (pdu[1] & 0xFF) != 4) {
            throw new IOException("Unexpected Modbus response format for unit " + unitId);
        }

        long raw = ((long) (pdu[2] & 0xFF) << 24)
                | ((long) (pdu[3] & 0xFF) << 16)
                | ((long) (pdu[4] & 0xFF) << 8)
                | (long) (pdu[5] & 0xFF);
        return BigDecimal.valueOf(raw).divide(SCALE_HUNDREDTHS_KWH, 3, RoundingMode.HALF_UP);
    }

    static HostPort parseHostPort(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Modbus host/port is required");
        }
        String cleaned = baseUrl.trim();
        int schemeIdx = cleaned.indexOf("://");
        if (schemeIdx >= 0) {
            cleaned = cleaned.substring(schemeIdx + 3);
        }
        int slashIdx = cleaned.indexOf('/');
        if (slashIdx >= 0) {
            cleaned = cleaned.substring(0, slashIdx);
        }
        if (cleaned.isBlank()) {
            throw new IllegalArgumentException("Modbus host/port is required");
        }
        int colonIdx = cleaned.lastIndexOf(':');
        if (colonIdx > 0 && colonIdx < cleaned.length() - 1) {
            String host = cleaned.substring(0, colonIdx).trim();
            int port = Integer.parseInt(cleaned.substring(colonIdx + 1).trim());
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Invalid Modbus port: " + port);
            }
            return new HostPort(host, port);
        }
        return new HostPort(cleaned, DEFAULT_PORT);
    }

    static List<Integer> parseUnitIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of(1);
        }
        String[] tokens = raw.split("[,;\\s]+");
        List<Integer> units = new ArrayList<>();
        for (String token : tokens) {
            if (token.isBlank()) {
                continue;
            }
            int id = Integer.parseInt(token.trim());
            if (id < 0 || id > 247) {
                throw new IllegalArgumentException("Invalid Modbus unit ID: " + id);
            }
            units.add(id);
        }
        return units.isEmpty() ? List.of(1) : units;
    }
}
