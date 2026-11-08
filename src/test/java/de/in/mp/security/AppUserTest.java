package de.in.mp.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AppUserTest {

    @Test
    void appliesSecureDefaultsOnCreation() {
        AppUser appUser = new AppUser();

        assertThat(appUser.isEnabled()).isTrue();
        assertThat(appUser.getRole()).isEqualTo(AppRole.ROLE_USER);
        assertThat(appUser.getAuthProvider()).isEqualTo(AuthProvider.LOCAL);
        assertThat(appUser.getPreferredLocale()).isEqualTo("de");
        assertThat(appUser.getPasswordHash()).isNull();
        assertThat(appUser.getProviderId()).isNull();
        assertThat(appUser.getFamilyMemberId()).isNull();
        assertThat(appUser.getLastLoginAt()).isNull();
    }
}
