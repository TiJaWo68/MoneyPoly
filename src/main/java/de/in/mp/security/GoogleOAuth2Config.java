package de.in.mp.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/**
 * Conditional Google OAuth2/OIDC client configuration.
 * <p>
 * The client registration repository is defined here so that the Spring Boot
 * OAuth2 client auto-configuration never builds a registration from an empty
 * {@code GOOGLE_CLIENT_ID}. When the client id is missing, an empty repository
 * is provided and the application starts without an OAuth2 login.
 */
@Configuration
public class GoogleOAuth2Config {

    public static final String REGISTRATION_ID = "google";

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(
            @Value("${GOOGLE_CLIENT_ID:}") String clientId,
            @Value("${GOOGLE_CLIENT_SECRET:}") String clientSecret) {
        if (!isConfigured(clientId)) {
            return registrationId -> null;
        }

        ClientRegistration googleRegistration = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "profile", "email")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
                .userNameAttributeName("sub")
                .jwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
                .clientName("Google")
                .build();

        return new InMemoryClientRegistrationRepository(googleRegistration);
    }

    /**
     * Determines whether a Google OAuth2 login can be offered.
     *
     * @param clientId the configured Google client id, may be {@code null} or blank
     * @return {@code true} when a non-blank client id is configured
     */
    public static boolean isConfigured(String clientId) {
        return clientId != null && !clientId.isBlank();
    }
}
