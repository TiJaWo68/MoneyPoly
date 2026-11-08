package de.in.mp.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

class GoogleOAuth2ConfigTest {

    private final GoogleOAuth2Config config = new GoogleOAuth2Config();

    @Test
    void treatsMissingOrBlankClientIdAsNotConfigured() {
        assertThat(GoogleOAuth2Config.isConfigured(null)).isFalse();
        assertThat(GoogleOAuth2Config.isConfigured("")).isFalse();
        assertThat(GoogleOAuth2Config.isConfigured("   ")).isFalse();
    }

    @Test
    void treatsNonBlankClientIdAsConfigured() {
        assertThat(GoogleOAuth2Config.isConfigured("google-client-id")).isTrue();
    }

    @Test
    void buildsEmptyRepositoryWhenClientIdIsMissing() {
        ClientRegistrationRepository repository = config.clientRegistrationRepository("", "secret");

        assertThat(repository.findByRegistrationId("google")).isNull();
    }

    @Test
    void buildsGoogleRegistrationWhenClientIdIsConfigured() {
        ClientRegistrationRepository repository = config.clientRegistrationRepository("client-id", "client-secret");

        ClientRegistration registration = repository.findByRegistrationId("google");

        assertThat(registration).isNotNull();
        assertThat(registration.getClientId()).isEqualTo("client-id");
        assertThat(registration.getClientSecret()).isEqualTo("client-secret");
        assertThat(registration.getScopes()).contains("openid", "profile", "email");
    }
}
