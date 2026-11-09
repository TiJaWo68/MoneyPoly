package de.in.mp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unit tests for the response mapping of {@link OpenIbanClient}.
 */
class OpenIbanClientTest {

    private final OpenIbanClient client = new OpenIbanClient(new ObjectMapper(), "http://localhost");

    @Test
    void mapsValidResponseToBankInfo() {
        OpenIbanClient.BankInfo info = new OpenIbanClient.BankInfo("37040044", "Commerzbank", "50447", "Köln",
                "COBADEFFXXX");
        OpenIbanClient.OpenIbanResponse response = new OpenIbanClient.OpenIbanResponse(true, info);

        assertThat(client.mapResponse(response)).contains(info);
    }

    @Test
    void returnsEmptyForInvalidOrMissingBankData() {
        assertThat(client.mapResponse(new OpenIbanClient.OpenIbanResponse(false, null))).isEmpty();
        assertThat(client.mapResponse(new OpenIbanClient.OpenIbanResponse(true, null))).isEmpty();
        assertThat(client.mapResponse(null)).isEmpty();
    }

    @Test
    void deserializesOpenIbanJson() throws Exception {
        String json = "{\"valid\":true,\"bankData\":{\"bankCode\":\"37040044\",\"name\":\"Commerzbank\","
                + "\"zip\":\"50447\",\"city\":\"Köln\",\"bic\":\"COBADEFFXXX\"}}";

        OpenIbanClient.OpenIbanResponse response = new ObjectMapper()
                .readValue(json, OpenIbanClient.OpenIbanResponse.class);

        assertThat(response.valid()).isTrue();
        assertThat(response.bankData().name()).isEqualTo("Commerzbank");
        assertThat(response.bankData().bic()).isEqualTo("COBADEFFXXX");
    }
}
