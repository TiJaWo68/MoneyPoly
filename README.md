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
