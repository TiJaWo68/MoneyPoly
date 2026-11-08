package de.in.mp.security;

import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import com.vaadin.flow.spring.security.VaadinSecurityConfigurer;

import de.in.mp.ui.LoginView;

/**
 * Application security configuration.
 * <p>
 * Protects all Vaadin views by default and supports two authentication paths:
 * local form login and Google OAuth2/OIDC. Authorization roles are always read
 * from the local {@code app_user} table.
 */
@EnableWebSecurity
public class SecurityConfig {

    private static final String[] OAUTH2_PUBLIC_ENDPOINTS = {
            "/oauth2/authorization/**",
            "/login/oauth2/code/**"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
            CustomOAuth2UserService customOAuth2UserService) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(OAUTH2_PUBLIC_ENDPOINTS).permitAll())
                .oauth2Login(oauth2 -> oauth2
                        .loginPage("/login")
                        .userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService)))
                .with(VaadinSecurityConfigurer.vaadin(), configurer -> configurer.loginView(LoginView.class));
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
