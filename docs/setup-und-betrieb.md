# Ersteinrichtung, Konfiguration & Betrieb

## Kurzzusammenfassung

Dieses Dokument beschreibt den technischen Betrieb von MoneyPoly: die Ersteinrichtung per einmaligem Setup-Token, Anmeldeverfahren (lokales Login, Google OAuth2/OIDC, Demo-Modus), die automatischen Flyway-Migrationen (`V1`–`V24`), sämtliche Umgebungsvariablen, die Einrichtung von **Open-Banking (Enable Banking & YAXI)**, optionales **Remote-TCP-Logging** sowie die relevanten **Maven-Build- und Testkommandos**.

---

## Ersteinrichtung (Setup)

Solange noch kein Benutzer in der Tabelle `app_user` existiert, erzeugt MoneyPoly beim Start einen einmaligen Setup-Token und gibt ihn im Server-Log aus:

```text
==============================================================
MONEYPOLY INITIAL SETUP TOKEN:  [ ABC23456 ]
Bitte diesen Token zur Freischaltung auf /setup eingeben.
==============================================================
```

1. `http://<host>:8080/setup` im Browser öffnen.
2. Benutzername, E-Mail-Adresse, Passwort (mindestens 8 Zeichen) sowie den Setup-Token eingeben.
3. Nach erfolgreicher Ersteinrichtung wird `/setup` dauerhaft gesperrt; alle nicht authentifizierten Zugriffe leiten auf `/login` um.

## Anmeldung & Demo-Modus

* **Lokales Login (`/login`)**: Benutzername/Passwort-Anmeldung mit BCrypt-gehashten Passwörtern.
* **Google OAuth2 / OIDC**: Sobald `GOOGLE_CLIENT_ID` und `GOOGLE_CLIENT_SECRET` gesetzt sind, erscheint auf `/login` automatisch der Button *„Mit Google anmelden“*.
* **Demo-Modus (`ROLE_DEMO`)**: Beim Start legt `DemoSeedService` bei Bedarf einen abgegrenzten Demo-Haushalt samt Beispieldaten an; im Kopfbereich erscheint für Demo-Sitzungen ein entsprechender Demo-Badge.
* **Reverse Proxy**: Hinter Caddy, Nginx oder Traefik sorgt `SERVER_FORWARD_HEADERS_STRATEGY=framework` (Standard in `application.yml`) für korrekte HTTPS-/OAuth2-Redirect-URLs.

## Datenbank & Flyway

MoneyPoly verwendet PostgreSQL mit `spring.jpa.hibernate.ddl-auto=none` und `spring.jpa.open-in-view=false`. Alle Schema-Änderungen werden beim Anwendungsstart automatisch über **Flyway** (`V1`–`V24`) eingespielt.

## Umgebungsvariablen

| Umgebungsvariable | Default | Beschreibung |
| --- | --- | --- |
| `MONEYPOLY_DB_URL` | `jdbc:postgresql://localhost:5432/moneypoly` | JDBC-URL der PostgreSQL-Datenbank |
| `MONEYPOLY_DB_USER` | `moneypoly` | Datenbank-Benutzer |
| `MONEYPOLY_DB_PASSWORD` | `moneypoly` | Datenbank-Passwort |
| `SERVER_FORWARD_HEADERS_STRATEGY` | `framework` | Header-Auswertung hinter einem Reverse Proxy (`X-Forwarded-*`) |
| `MONEYPOLY_OPENIBAN_BASE_URL` | `https://openiban.com` | Basis-URL für IBAN-/Bankverzeichnis-Abfragen via OpenIBAN |
| `GOOGLE_CLIENT_ID` | *leer* | Client-ID für Google-OAuth2-Login |
| `GOOGLE_CLIENT_SECRET` | *leer* | Client-Secret für Google-OAuth2-Login |
| `LOGGING_TCP_ENABLED` | `false` | Aktiviert strukturiertes Logstash-JSON-Log-Streaming via TCP |
| `LOGGING_TCP_DESTINATIONS` | `${LOGGING_TCP_HOST}:${LOGGING_TCP_PORT}` | Komma-separierte Ziel-Liste mit Failover (z. B. `192.168.178.110:4560,192.168.178.135:4560`) |
| `LOGGING_TCP_HOST` | `127.0.0.1` | Fallback-Host des TCP-Log-Receivers (z. B. Chainsaw / Logstash / Lilith) |
| `LOGGING_TCP_PORT` | `4560` | Fallback-Port des TCP-Log-Receivers |
| `MONEYPOLY_CRYPTO_KEY` | *leer* | Master-Key für AES-256-GCM-Verschlüsselung von Open-Banking-Sessions (`openssl rand -base64 32`) |
| `MONEYPOLY_ENABLE_BANKING_BASE_URL` | `https://api.enablebanking.com` | Basis-URL der Enable-Banking-API |
| `MONEYPOLY_ENABLE_BANKING_APP_ID` | *leer* | Enable Banking Application-ID |
| `MONEYPOLY_ENABLE_BANKING_PRIVATE_KEY_PATH` | *leer* | Pfad zur Enable-Banking-`.pem`-Datei (RSA PKCS#8 Private Key für RS256-JWT) |
| `MONEYPOLY_ENABLE_BANKING_PRIVATE_KEY` | *leer* | Alternativ: Enable-Banking-Private-Key (`.pem`) direkt als String |
| `MONEYPOLY_ENABLE_BANKING_TOKEN` | *leer* | Alternativ: vorab signiertes Enable-Banking-JWT |
| `MONEYPOLY_ENABLE_BANKING_CONSENT_DAYS` | `180` | Gültigkeitsdauer angeforderter Enable-Banking-Consents in Tagen |
| `MONEYPOLY_YAXI_BASE_URL` | `https://api.yaxi.tech` | Basis-URL der YAXI-API |
| `MONEYPOLY_YAXI_API_KEY` | *leer* | YAXI API-Key (interaktives AIS inkl. Trade Republic) |
| `MONEYPOLY_BANKSYNC_SCHEDULING_ENABLED` | `true` | Aktiviert/deaktiviert den nächtlichen automatischen Open-Banking-Sync |
| `MONEYPOLY_BANKSYNC_CRON` | `0 0 3 * * *` | Cron-Ausdruck für den nächtlichen Hintergrund-Sync |

## Open-Banking (PSD2 / AIS) einrichten

MoneyPoly unterstützt optional den rein lesenden Abruf von Kontoständen und Umsätzen über **Enable Banking** (hintergrundfähiger Nacht-Sync, 90–180 Tage Consent) und **YAXI** (interaktives Ticket/Widget, u. a. für Trade Republic).

1. **Master-Key für Session-Verschlüsselung erzeugen:**

   ```bash
   openssl rand -base64 32
   ```

   Diesen Wert als `MONEYPOLY_CRYPTO_KEY` setzen. Ohne gesetzten Key startet MoneyPoly weiterhin normal, deaktiviert aber das Anlegen neuer verschlüsselter Bankverbindungen.
2. **Provider-Zugangsdaten hinterlegen:**
   * **Enable Banking**: Im Enable-Banking-Dashboard die Redirect-URL (z. B. `https://<host>/finance?tab=accounts`) freischalten und `MONEYPOLY_ENABLE_BANKING_APP_ID` sowie `MONEYPOLY_ENABLE_BANKING_PRIVATE_KEY_PATH` (oder `..._PRIVATE_KEY` / `..._TOKEN`) setzen.
   * **YAXI**: API-Key (`MONEYPOLY_YAXI_API_KEY`) im YAXI-Portal erstellen und hinterlegen.
3. **Umgebungsvariablen im systemd-Service hinterlegen** (z. B. `/etc/systemd/system/moneypoly.service.d/override.conf`):

   ```ini
   [Service]
   Environment="MONEYPOLY_CRYPTO_KEY=<output-von-openssl-rand-base64-32>"
   Environment="MONEYPOLY_ENABLE_BANKING_APP_ID=<deine-app-id>"
   Environment="MONEYPOLY_ENABLE_BANKING_PRIVATE_KEY_PATH=/etc/moneypoly/enablebanking.pem"
   Environment="MONEYPOLY_YAXI_API_KEY=<dein-yaxi-api-key>"
   ```

   Danach Dienst neu laden und starten:

   ```bash
   systemctl daemon-reload
   systemctl restart moneypoly
   ```

4. **Bankkonto verknüpfen**: Unter *Finanzen → Konten → Konto bearbeiten → „Bankverbindungen verwalten…“* Provider und Bank auswählen, externe Bankfreigabe abschließen und optional den Nacht-Sync aktivieren.

## Remote-Log-Streaming (TCP)

Log-Events können in Echtzeit asynchron im Logstash-JSON-Format an einen oder mehrere TCP-Receiver gestreamt werden (automatisches Failover über mehrere Ziele hinweg, Reconnect bei Unterbrechung, blockiert die App nicht bei nicht erreichbarem Ziel):

```bash
# Mehrere Ziele (z. B. WLAN- und Docking-Station-IP):
LOGGING_TCP_ENABLED=true \
LOGGING_TCP_DESTINATIONS="192.168.178.110:4560,192.168.178.135:4560" \
java -jar moneypoly.jar

# Oder klassisch mit einem einzelnen Ziel:
LOGGING_TCP_ENABLED=true \
LOGGING_TCP_HOST=192.168.178.110 \
LOGGING_TCP_PORT=4560 \
java -jar moneypoly.jar
```

## Build & Test (Entwicklung)

| Kommando | Beschreibung |
| --- | --- |
| `mvn clean test` | Führt alle Unit- und UI-Tests aus (schnell; Testcontainers-Integrationstests übersprungen) |
| `mvn clean test -Pintegration-tests` | Führt zusätzlich alle Integrationstests mit Testcontainers (PostgreSQL in Docker) aus |
| `mvn clean package -Pproduction -DskipTests` | Baut das produktionsreife Fat-JAR inklusive kompilierter und minifizierter Vaadin-Frontend-Assets |
| `mvn spring-boot:run` | Startet die Anwendung lokal im Entwicklungsmodus |
