package de.in.mp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class SetupServiceTest {

    private AppUserRepository repository;
    private PasswordEncoder passwordEncoder;
    private SetupService service;

    @BeforeEach
    void setUp() {
        repository = mock(AppUserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        service = new SetupService(repository, passwordEncoder);
    }

    @Test
    void createsAdminWithEncodedPasswordAndLocalProvider() {
        when(repository.count()).thenReturn(0L);
        when(passwordEncoder.encode("secret123")).thenReturn("hashed");
        when(repository.save(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AppUser result = service.createInitialAdmin("admin", "admin@example.com", "secret123");

        assertThat(result.getUsername()).isEqualTo("admin");
        assertThat(result.getEmail()).isEqualTo("admin@example.com");
        assertThat(result.getPasswordHash()).isEqualTo("hashed");
        assertThat(result.getRole()).isEqualTo(AppRole.ROLE_ADMIN);
        assertThat(result.getAuthProvider()).isEqualTo(AuthProvider.LOCAL);
        assertThat(result.isEnabled()).isTrue();
        verify(repository).save(any(AppUser.class));
    }

    @Test
    void rejectsSetupWhenAUserAlreadyExists() {
        when(repository.count()).thenReturn(1L);

        assertThatThrownBy(() -> service.createInitialAdmin("admin", "admin@example.com", "secret123"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");

        verify(repository, never()).save(any(AppUser.class));
        verify(passwordEncoder, never()).encode(any(CharSequence.class));
    }
}
