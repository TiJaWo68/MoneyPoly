package de.in.mp.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class SecurityConfigTest {

    @Test
    void providesBcryptPasswordEncoder() {
        SecurityConfig config = new SecurityConfig();

        assertThat(config.passwordEncoder()).isInstanceOf(BCryptPasswordEncoder.class);
    }
}
