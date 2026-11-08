package de.in.mp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

class CustomOAuth2UserServiceTest {

    private AppUserRepository repository;
    private CustomOAuth2UserService service;

    @BeforeEach
    void setUp() {
        repository = mock(AppUserRepository.class);
        service = new CustomOAuth2UserService(repository);
    }

    @Test
    void mapsLocalRoleAndAttributesForKnownProviderId() {
        AppUser appUser = new AppUser();
        appUser.setRole(AppRole.ROLE_ADMIN);
        appUser.setEnabled(true);
        when(repository.findByProviderId("google-sub")).thenReturn(Optional.of(appUser));

        OAuth2User result = service.buildOAuth2User(googleUser("google-sub", "admin@example.com"));

        assertThat(result.getAttributes()).containsEntry("sub", "google-sub");
        assertThat(result.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void fallsBackToEmailLookupWhenProviderIdIsUnknown() {
        AppUser appUser = new AppUser();
        appUser.setRole(AppRole.ROLE_USER);
        appUser.setEnabled(true);
        when(repository.findByProviderId("google-sub")).thenReturn(Optional.empty());
        when(repository.findByEmail("user@example.com")).thenReturn(Optional.of(appUser));

        OAuth2User result = service.buildOAuth2User(googleUser("google-sub", "user@example.com"));

        assertThat(result.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
    }

    @Test
    void rejectsUnknownEmail() {
        when(repository.findByProviderId("google-sub")).thenReturn(Optional.empty());
        when(repository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.buildOAuth2User(googleUser("google-sub", "unknown@example.com")))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("unknown@example.com");
    }

    @Test
    void rejectsDisabledAccount() {
        AppUser appUser = new AppUser();
        appUser.setEnabled(false);
        when(repository.findByProviderId("google-sub")).thenReturn(Optional.of(appUser));

        assertThatThrownBy(() -> service.buildOAuth2User(googleUser("google-sub", "user@example.com")))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void rejectsOAuth2UserWithoutEmailAttribute() {
        when(repository.findByProviderId("google-sub")).thenReturn(Optional.empty());

        OAuth2User userWithoutEmail = new DefaultOAuth2User(List.of(), Map.of("sub", "google-sub"), "sub");

        assertThatThrownBy(() -> service.buildOAuth2User(userWithoutEmail))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("No enabled account");
    }

    private OAuth2User googleUser(String subject, String email) {
        Map<String, Object> attributes = Map.of("sub", subject, "email", email);
        return new DefaultOAuth2User(List.of(), attributes, "sub");
    }
}
