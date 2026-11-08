package de.in.mp.ui.layout;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.annotation.security.PermitAll;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

class MainLayoutTest {

    @Test
    void rendersCommonNavigationEntriesForEveryRole() {
        for (MainLayout.NavigationItem item : MainLayout.navigationItems()) {
            if (item.labelKey().equals("ui.nav.admin")) {
                continue;
            }
            assertThat(MainLayout.isItemVisible(item, Set.of("ROLE_USER")))
                    .as("Common entry %s should be visible for ROLE_USER", item.labelKey())
                    .isTrue();
        }
    }

    @Test
    void rendersAdminEntryOnlyForAdminRole() {
        MainLayout.NavigationItem admin = adminItem();

        assertThat(MainLayout.isItemVisible(admin, Set.of("ROLE_USER"))).isFalse();
        assertThat(MainLayout.isItemVisible(admin, Set.of("ROLE_ADMIN"))).isTrue();
    }

    @Test
    void exposesAllExpectedNavigationEntriesInOrder() {
        assertThat(MainLayout.navigationItems())
                .extracting(MainLayout.NavigationItem::labelKey)
                .containsExactly("ui.nav.dashboard", "ui.nav.finance", "ui.nav.loans", "ui.nav.energy", "ui.nav.admin");
    }

    @Test
    void resolvesDisplayNameForLocalUser() {
        UserDetails principal = User.withUsername("till").password("x").roles("USER").build();
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());

        assertThat(MainLayout.resolveDisplayName(authentication)).isEqualTo("till");
    }

    @Test
    void resolvesDisplayNameForOAuth2UserByEmail() {
        OAuth2User principal = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of("sub", "google-sub", "email", "till@example.com"),
                "sub");
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());

        assertThat(MainLayout.resolveDisplayName(authentication)).isEqualTo("till@example.com");
    }

    @Test
    void returnsEmptyDisplayNameWhenNotAuthenticated() {
        assertThat(MainLayout.resolveDisplayName(null)).isEmpty();
    }

    @Test
    void mainLayoutAllowsAuthenticatedAccess() {
        assertThat(MainLayout.class.isAnnotationPresent(PermitAll.class))
                .as("MainLayout must allow access for every authenticated user")
                .isTrue();
    }

    private MainLayout.NavigationItem adminItem() {
        return MainLayout.navigationItems().stream()
                .filter(item -> item.labelKey().equals("ui.nav.admin"))
                .findFirst()
                .orElseThrow();
    }
}
