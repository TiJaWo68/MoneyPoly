## Ziel
Sichere Authentifizierung und Autorisierung mit strikter Trennung von technischer Identität (`app_user`) und fachlichen Domänen. Unterstützung von lokalem Passwort-Login (BCrypt) und Google OAuth2/OIDC (Whitelisting).

## Akzeptanzkriterien
- [x] Flyway-Migration `V2__security.sql` mit Tabelle `app_user` und Rollen-Enum (`ROLE_ADMIN`, `ROLE_USER`)
- [x] `AppUser`-Entity und `AppUserRepository` implementiert
- [x] Lokale Authentifizierung via Spring Security `UserDetailsService` und `BCryptPasswordEncoder`
- [x] Google-OAuth2-Login via `spring-boot-starter-oauth2-client` mit Whitelisting-Check über `CustomOAuth2UserService`
- [x] Konfiguration via `SecurityConfig` (basierend auf Vaadin-Security)
- [x] Responsive `LoginView` im Dark-Design mit lokalem Formular und „Mit Google anmelden“-Button
- [x] Alle Fehler- und Hinweistexte per i18n (`messages_*.properties`) abgebildet
- [x] Vollständige Unit- und Mock-Tests für Authentifizierungs-Pfade
