# MoneyPoly

Selbst gehostete Webanwendung für Haushaltsfinanz-Kaskaden, private Familienkredite sowie Energie-, PV- und Wallbox-Amortisation („Black Zero").

## Technik-Stack

- Java 25, Spring Boot, Vaadin Flow
- PostgreSQL mit Flyway-Migrationen
- Spring Security (Form-Login und optionales Google OAuth2/OIDC)
- GitHub Actions für CI-Builds und automatische Releases

## Ersteinrichtung (Setup)

Solange noch kein Benutzer in `app_user` existiert, erzeugt die Anwendung beim Start einen einmaligen Setup-Token und gibt ihn im Server-Log aus:

```
==============================================================
MONEYPOLY INITIAL SETUP TOKEN:  [ ABC23456 ]
Bitte diesen Token zur Freischaltung auf /setup eingeben.
==============================================================
```

1. Öffne `http://<host>:8080/setup`.
2. Vergib Benutzername, E-Mail, Passwort (min. 8 Zeichen) und den Setup-Token.
3. Nach erfolgreicher Einrichtung wird `/setup` dauerhaft gesperrt; unauthentifizierte Zugriffe landen auf `/login`.

## Anmeldung

- Lokales Form-Login unter `/login` mit BCrypt-gehashten Passwörtern.
- Optionales Google OAuth2/OIDC: Der „Mit Google anmelden"-Link erscheint nur, wenn `GOOGLE_CLIENT_ID` konfiguriert ist.

## Konfiguration

| Umgebungsvariable | Default | Beschreibung |
| --- | --- | --- |
| `MONEYPOLY_DB_URL` | `jdbc:postgresql://localhost:5432/moneypoly` | JDBC-URL der PostgreSQL-Datenbank |
| `MONEYPOLY_DB_USER` | `moneypoly` | Datenbank-Benutzer |
| `MONEYPOLY_DB_PASSWORD` | `moneypoly` | Datenbank-Passwort |
| `GOOGLE_CLIENT_ID` | leer | Aktiviert den Google-OAuth2-Login |

Die Flyway-Migrationen (`V1`–`V3`) werden beim Start automatisch angewendet.

## Download & Ausführung

1. Lade das aktuelle `moneypoly.jar` aus den [GitHub Releases](https://github.com/TiJaWo68/MoneyPoly/releases) herunter.
2. Starte die Anwendung mit:

   ```bash
   MONEYPOLY_DB_URL=jdbc:postgresql://localhost:5432/moneypoly \
   MONEYPOLY_DB_USER=finanzuser \
   MONEYPOLY_DB_PASSWORD=secret \
   java -jar moneypoly.jar
   ```

> Mindestvoraussetzung: Java 25 Laufzeitumgebung.

## Build & Test

| Kommando | Beschreibung |
| --- | --- |
| `mvn clean test` | Kompiliert und führt die Unit-Tests aus; Integrationstests werden übersprungen |
| `mvn clean test -Pintegration-tests` | Führt zusätzlich die Integrationstests aus (Testcontainers PostgreSQL, benötigt Docker) |
| `mvn clean verify` | Vollständiger Build inklusive Tests |
| `mvn spring-boot:run -Dspring-boot.run.profiles=dev` | Startet die Anwendung lokal |

## Remote-Log-Streaming (TCP)

Log-Events können in Echtzeit über einen TCP-Socket an einen externen Log-Receiver (z. B. Chainsaw, Lilith oder LogSyncPro) gestreamt werden. Die Ausgabe erfolgt strukturiert im Logstash-JSON-Format. Der Appender puffert asynchron und blockiert die Anwendung nicht, falls der Receiver nicht erreichbar ist; die Verbindung wird automatisch neu aufgebaut (Reconnection).

| Property | Umgebungsvariable | Default | Beschreibung |
| --- | --- | --- | --- |
| `moneypoly.logging.tcp.enabled` | `LOGGING_TCP_ENABLED` | `false` | Aktiviert den TCP-Socket-Appender |
| `moneypoly.logging.tcp.host` | `LOGGING_TCP_HOST` | `192.168.178.x` | Ziel-Host des TCP-Log-Receivers |
| `moneypoly.logging.tcp.port` | `LOGGING_TCP_PORT` | `4560` | Ziel-Port des TCP-Log-Receivers |

Beispiel (Aktivierung per Umgebungsvariablen):

   ```bash
   LOGGING_TCP_ENABLED=true \
   LOGGING_TCP_HOST=192.168.178.50 \
   LOGGING_TCP_PORT=4560 \
   java -jar moneypoly.jar
   ```
