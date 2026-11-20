# MoneyPoly

Selbst gehostete Webanwendung zur Verwaltung von Haushaltsfinanzen, Wertpapierdepots, privaten Familienkrediten, Energie- & PV-Amortisation („Black Zero“) sowie optionaler persönlicher Arbeitszeiterfassung.

## Inhaltliche Kurzvorstellung

MoneyPoly bündelt zentrale Finanz-, Vertrags- und Hausdaten für Familien und Mehrpersonen-Haushalte unter einer gemeinsamen, auf Deutsch und Englisch verfügbaren Weboberfläche. Jeder Hauptbereich besitzt im linken Navigationsmenü einen eigenen Menüpunkt; je nach Benutzerrolle und Freischaltung werden nur relevante Bereiche eingeblendet:

| Menüpunkt | Kurzzusammenfassung | Dokumentation |
| --- | --- | --- |
| **Dashboard** (`/`) | Zentrale Gesamtübersicht über Nettovermögen, Liquidität, Investments (inkl. Yahoo-Kursaktualisierung & Performance), Tagesgeld-Zinswarnungen und offene Privatkredite. | [docs/dashboard.md](docs/dashboard.md) |
| **Finanzen** (`/finance`) | Multi-Haushalts-Vermögensverwaltung mit interaktivem Verlaufschart (Zoom, Pan, Skalierung, Doppelklick-Ereignisse), Stichtags-Salden, Kontenhierarchie, Depotpositionen, PDF/ODS-Import, Open-Banking (Enable Banking / YAXI) und optionalem Ausstiegsplan. | [docs/finanzen.md](docs/finanzen.md) |
| **Private-Kredite** (`/loans`) | Verwaltung privater Darlehensverträge, Ratenpläne, Sondertilgungen und offener Restforderungen inklusive eingeschränkter Nur-Lese-Rolle für Darlehensnehmer. | [docs/private-kredite.md](docs/private-kredite.md) |
| **Energie** (`/energy`) | Multi-Haus-Zählerverwaltung (Strom, Gas, Wasser, PV, Wärmepumpe, Wallbox), KI-Foto- & Screenshot-Import mit Fotoarchiv, Tarifverwaltung, „Black Zero“-Amortisation und PDF-Rechnungsprüfung inkl. Widerspruchs-Entwurf. | [docs/energie.md](docs/energie.md) |
| **Arbeitszeit** (`/work-time`, optional) | Erst nach Aktivierung sichtbare, persönliche Arbeitszeiterfassung für getrennte Tagesblöcke (z. B. Schule, Homeoffice, Konferenz, Pendeln), pro-rata-Deputatsberechnung (z. B. `20/27` UE), Gesundheits-/Ruhezeitschutz (`> 10h`, `< 11h`) und CSV-Export. | [docs/arbeitszeit.md](docs/arbeitszeit.md) |
| **Administration** (`/admin`) | Benutzer-, Rollen- und Zugriffsverwaltung (Haushalte, Häuser, Arbeitszeit-Profile), Personen, Kategorien, Klassifizierungsregeln, Vision-KI- & Schwellenwerteinstellungen, ODS-Datenmigration/Reset sowie Systemzustand. | [docs/administration.md](docs/administration.md) |

Ergänzende Installations- und Betriebshinweise (Ersteinrichtung per Setup-Token, Login/OAuth2, Demo-Modus, Umgebungsvariablen, Flyway, Open-Banking-Konfiguration, TCP-Log-Streaming und Maven-Builds) finden sich in [docs/setup-und-betrieb.md](docs/setup-und-betrieb.md).

## Technische Kurzvorstellung

* **Backend**: Java 25 (OpenJDK / Eclipse Temurin), Spring Boot 4
* **Frontend**: Vaadin Flow 25 (Full-Stack Java, Lumo Light/Dark-Theme, serverseitiges Event-Handling, interaktive SVG-Charts, Smartphone- & Landscape-Responsivität)
* **Persistenz**: PostgreSQL, Spring Data JPA / Hibernate (`ddl-auto: none`, `open-in-view: false`), Flyway (`V1`–`V24`)
* **Sicherheit & Rollen**: Spring Security mit Form-Login (BCrypt), optionalem Google OAuth2/OIDC, AES-256-GCM-verschlüsselten Open-Banking-Sessions sowie vier Rollen (`ROLE_ADMIN`, `ROLE_USER`, `ROLE_BORROWER`, `ROLE_DEMO`)
* **Externe Schnittstellen (optional)**: Enable Banking & YAXI (PSD2/AIS Read-Only), Yahoo Finance (Depotkurse), OpenIBAN (Bankverzeichnis/BIC), OpenAI-kompatible Vision-APIs (Gemini, OpenAI, Ollama, Custom)
* **Testing & Observability**: JUnit 5, Mockito, Testcontainers (PostgreSQL), Logback mit optionalem TCP-Logstash-JSON-Streaming
* **CI/CD**: GitHub Actions (CI-Test-Suite & Release-Artifacts)

## Schnellstart

1. Aktuelles `moneypoly.jar` aus den [GitHub Releases](https://github.com/TiJaWo68/MoneyPoly/releases) laden (Voraussetzung: Java 25 + PostgreSQL).
2. Anwendung starten:

   ```bash
   MONEYPOLY_DB_URL=jdbc:postgresql://localhost:5432/moneypoly \
   MONEYPOLY_DB_USER=moneypoly \
   MONEYPOLY_DB_PASSWORD=secret \
   java -jar moneypoly.jar
   ```

3. Beim ersten Start den im Server-Log ausgegebenen Setup-Token unter `http://<host>:8080/setup` eingeben und den ersten Admin-Benutzer anlegen.

Alle Details zu Umgebungsvariablen, Reverse-Proxy/TLS-Betrieb, Open-Banking-Einrichtung und Maven-Kommandos stehen unter [docs/setup-und-betrieb.md](docs/setup-und-betrieb.md).
