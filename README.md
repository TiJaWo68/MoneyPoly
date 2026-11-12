# MoneyPoly

Selbst gehostete Webanwendung für Haushaltsfinanz-Kaskaden, private Familienkredite sowie Energie-, PV- und Wallbox-Amortisation („Black Zero").

## Funktionsumfang

* **Dashboard**: Zentrale Übersicht über Gesamtvermögen, Liquidität, Investments und offene Private-Kredite mit responsivem KPI-Layout.
* **Finanzen**:
  * Strukturierte Kontoführung (Girokonto, Tagesgeld/Sparen, Depot/Wertpapiere, etc.) mit Hierarchie- und Inhaber-Zuordnung.
  * Interaktive Vermögensvisualisierung: Dynamisches Strecken- und Flächendiagramm (Stacked Area) mit Zeitraum-Schiebereglern und Schnellwahl-Filtern (1J, 3J, 5J, 10J, Max).
  * Stichtagsbezogene Kontostands-Matrix und historische Salden.
  * Automatisierter Kontoauszug-Import (PDF für C24, Targobank; OpenDocument Spreadsheet ODS).
  * Integrierte Bankverzeichnis-Abfrage via OpenIBAN / BIC.
* **Private-Kredite**: Verwaltung von Kreditverträgen, Ratenplänen, Sondertilgungen und offenen Restforderungen im Familien- und Freundeskreis.
* **Energie & Amortisation („Black Zero")**:
  * Zähler- und Zählerstandsverwaltung (Strom, Gas, Wasser, PV-Einspeisung/Erzeugung, Wärmepumpe).
  * Tarifverwaltung für Arbeitspreise und Grundgebühren.
  * Wirtschaftlichkeits- und Amortisationsberechnung für Investitionsgüter (PV-Anlagen, Wallboxen, Speicher).
* **Administration**:
  * Benutzer- und Rollenverwaltung (`ROLE_ADMIN`, `ROLE_USER`).
  * Personenverwaltung und Kategorisierungsregeln für Buchungen.
  * Datenbank-Reset- und Datenmigrations-Werkzeuge.
* **Mobile Responsiveness**: Optimierte Benutzeroberfläche für Mobilgeräte und Smartphones (Touch-freundliche Filter, automatische KPI-Karten-Umbrüche und kollapssichere Tabellen-Layouts).

## Technik-Stack

* **Backend**: Java 25 (OpenJDK / Eclipse Temurin), Spring Boot 4
* **Frontend**: Vaadin Flow 25 (Full-Stack Java, Lumo Dark/Light-Theme, serverseitiges Event-Handling, SVG-Visualisierung)
* **Persistenz**: PostgreSQL, Spring Data JPA / Hibernate (`ddl-auto: none`, `open-in-view: false`)
* **Datenbank-Migrationen**: Flyway (`V1`–`V12`)
* **Sicherheit**: Spring Security (Form-Login mit BCrypt und optionales Google OAuth2/OIDC)
* **Testing**: JUnit 5, Mockito, Testcontainers (PostgreSQL)
* **Logging**: Logback mit Logstash-JSON-Streaming über TCP
* **CI/CD**: GitHub Actions (CI Test-Suite & Release-Artifacts)

## Ersteinrichtung (Setup)

Solange noch kein Benutzer in `app_user` existiert, erzeugt die Anwendung beim Start einen einmaligen Setup-Token und gibt ihn im Server-Log aus:

```text
==============================================================
MONEYPOLY INITIAL SETUP TOKEN:  [ ABC23456 ]
Bitte diesen Token zur Freischaltung auf /setup eingeben.
==============================================================
```

1. Öffne `http://<host>:8080/setup`.
2. Vergib Benutzername, E-Mail, Passwort (min. 8 Zeichen) und den Setup-Token.
3. Nach erfolgreicher Einrichtung wird `/setup` dauerhaft gesperrt; unauthentifizierte Zugriffe landen auf `/login`.

## Anmeldung

* Lokales Form-Login unter `/login` mit BCrypt-gehashten Passwörtern.
* Optionales Google OAuth2/OIDC: Der „Mit Google anmelden"-Button erscheint automatisch, wenn `GOOGLE_CLIENT_ID` konfiguriert ist.

## Konfiguration

| Umgebungsvariable | Default | Beschreibung |
| --- | --- | --- |
| `MONEYPOLY_DB_URL` | `jdbc:postgresql://localhost:5432/moneypoly` | JDBC-URL der PostgreSQL-Datenbank |
| `MONEYPOLY_DB_USER` | `moneypoly` | Datenbank-Benutzer |
| `MONEYPOLY_DB_PASSWORD` | `moneypoly` | Datenbank-Passwort |
| `MONEYPOLY_OPENIBAN_BASE_URL` | `https://openiban.com` | Basis-URL für Bank-Lookups via OpenIBAN |
| `GOOGLE_CLIENT_ID` | *leer* | Client-ID für Google-OAuth2-Login |
| `GOOGLE_CLIENT_SECRET` | *leer* | Client-Secret für Google-OAuth2-Login |
| `LOGGING_TCP_ENABLED` | `false` | Aktiviert strukturiertes JSON-Log-Streaming via TCP |
| `LOGGING_TCP_DESTINATIONS` | `${LOGGING_TCP_HOST}:${LOGGING_TCP_PORT}` | Komma-separierte Liste von Log-Zielen mit Failover (z. B. `192.168.178.110:4560,192.168.178.135:4560` für WLAN + Docking-LAN) |
| `LOGGING_TCP_HOST` | `127.0.0.1` | Fallback-Host des Log-Receivers (z. B. Chainsaw / Logstash) |
| `LOGGING_TCP_PORT` | `4560` | Fallback-Port des TCP-Log-Receivers |

Die Flyway-Migrationen (`V1`–`V12`) werden beim Start automatisch angewendet.

## Download & Ausführung

1. Lade das aktuelle `moneypoly.jar` aus den [GitHub Releases](https://github.com/TiJaWo68/MoneyPoly/releases) herunter.
2. Starte die Anwendung mit:

   ```bash
   MONEYPOLY_DB_URL=jdbc:postgresql://localhost:5432/moneypoly \
   MONEYPOLY_DB_USER=moneypoly \
   MONEYPOLY_DB_PASSWORD=secret \
   java -jar moneypoly.jar
   ```

> Mindestvoraussetzung: Java 25 Laufzeitumgebung.

## Build & Test

| Kommando | Beschreibung |
| --- | --- |
| `mvn clean test` | Führt alle Unit- und UI-Tests aus (schnell, ca. 15s; Integrationstests übersprungen) |
| `mvn clean test -Pintegration-tests` | Führt zusätzlich alle Integrationstests mit Testcontainers (PostgreSQL in Docker) aus |
| `mvn clean package -Pproduction -DskipTests` | Baut das produktionsreife Fat-JAR inklusive kompilierter und minifizierter Vaadin-Frontend-Assets |
| `mvn spring-boot:run` | Startet die Anwendung lokal im Entwicklungsmodus |

## Remote-Log-Streaming (TCP)

Log-Events können in Echtzeit über einen TCP-Socket an einen externen Log-Receiver (z. B. Chainsaw, Lilith, Logstash) gestreamt werden. Die Ausgabe erfolgt strukturiert im Logstash-JSON-Format. Der Appender puffert asynchron und blockiert die Anwendung nicht, falls der Receiver nicht erreichbar ist; Verbindungsabbrüche werden automatisch durch Reconnects abgefangen.

Beispiel (einzelnes Ziel oder mehrere Ziele mit automatischem Failover):

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
