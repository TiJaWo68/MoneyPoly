# MoneyPoly

Selbst gehostete Webanwendung für Haushaltsfinanz-Kaskaden, private Familienkredite sowie Energie-, PV- und Wallbox-Amortisation („Black Zero").

## Technik-Stack

- Java 25, Spring Boot, Vaadin Flow
- PostgreSQL mit Flyway-Migrationen
- GitHub Actions für CI-Builds und automatische Releases

## Download & Ausführung

1. Lade das aktuelle `moneypoly.jar` aus den [GitHub Releases](https://github.com/TiJaWo68/MoneyPoly/releases) herunter.
2. Starte die Anwendung mit:

   ```bash
   SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/moneypoly \
   SPRING_DATASOURCE_USERNAME=finanzuser \
   SPRING_DATASOURCE_PASSWORD=secret \
   java -jar moneypoly.jar
   ```

> Mindestvoraussetzung: Java 25 Laufzeitumgebung.

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
