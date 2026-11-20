# Administration (`/admin`)

## Kurzzusammenfassung

Der ausschließlich für Administratoren (`ROLE_ADMIN`) zugängliche Bereich **Administration** bündelt in sieben Tabs alle Stammdaten-, Berechtigungs-, KI- und Systemfunktionen: Benutzerkonten (inklusive Haushalten, Hausrechten und Arbeitszeit-Freischaltung), Personen, Buchungskategorien, automatische Klassifizierungsregeln, globale Einstellungen (Zinswarnschwelle & Vision-KI), Datenmigration/Reset sowie Live-Systemdiagnose.

---

## Tabs im Administrationsbereich

### 1. Benutzer (`UsersTab`)

Verwaltung aller technischen Login-Konten (`AppUser`) und deren fachlicher Zuordnungen:

* **Anlegen, Bearbeiten, Sperren, Löschen & Passwort-Reset** lokaler Konten (OAuth2-Konten werden ohne lokales Passwort geführt).
* **Rollenzuweisung (`AppRole`)**:
  * `ROLE_ADMIN`: Vollzugriff inklusive Administration und aller Arbeitszeit-Profile (sofern aktiviert).
  * `ROLE_USER`: Regulärer Haushaltsbenutzer.
  * `ROLE_BORROWER`: Eingeschränkte Nur-Lese-Rolle für Privatkredite (ohne Finanz-/Energiezugriff).
  * `ROLE_DEMO`: Abgegrenzter Demo-Benutzer.
* **Fachliche Verknüpfungen im Benutzer-Editor (`UserEditorDialog`)**:
  * Zuordnung zu einer *Person* (`Person`) und einem *Haushalt* (`Household`).
  * Verwaltung von Haushalten (*„Haushalte verwalten…“* in `UsersTab`, inkl. Option *Ausstiegsplan aktivieren*).
  * Zuweisung von Häusern (`EnergyHouse`) und Zugriffsrolle (`OWNER`, `EDITOR`, `VIEWER`) für den Bereich Energie.
  * **Optionale Arbeitszeiterfassung**: Aktivierung des persönlichen Arbeitszeitmoduls inklusive Deputats- und Schwellenwert-Konfiguration (`20/27` UE usw.) mit Live-Vorschau von Anteil und bezahlter Sollzeit.

### 2. Personen (`PersonsTab`)

Pflege natürlicher Personen (`Person`), die als Kontoinhaber auf Konten oder als Kreditgeber/-nehmer in Privatkrediten referenziert werden.

### 3. Kategorien (`CategoriesTab`)

Verwaltung von Einnahmen- und Ausgabenkategorien inklusive Farbcodierung für Auswertungen und Regelwerke.

### 4. Regeln (`ClassificationRulesTab`)

Regelbasierte Klassifizierung von Buchungen und Transaktionen (Musterabgleich auf Empfänger/Verwendungszweck mit Zielkategorie oder optionaler Zuordnung zu einem Privatkredit-Vertrag).

### 5. Einstellungen (`SettingsTab`)

Globale Fach- und Schnittstellen-Konfiguration (`AppSettingService`):

* **Tagesgeld-Zinswarnschwelle (%)**: Schwellenwert, unterhalb dessen Konten auf dem Dashboard und im Finanzen-Bereich als zinskritisch hervorgehoben werden.
* **KI- / Vision-Einstellungen (für den Zählerfoto-Import)**:
  * **Presets (`VisionPreset`)**: `DISABLED`, `GEMINI` (`gemini-2.5-flash`), `OPENAI` (`gpt-4o-mini`), `OLLAMA` (`http://localhost:11434/v1`, `qwen2.5vl:7b`), sowie `CUSTOM`.
  * **Base-URL, API-Key & Modellauswahl**: Mit Buttons zum Live-Abruf verfügbarer Modelle vom Endpunkt und einem direkten **Verbindungstest**.

### 6. Datenmigration & Reset (`DataMigrationTab`)

Werkzeuge zur Ersteinrichtung und für wiederholbare Tests:

* **Finanzen-ODS-Migration** (`FinanceOdsMigrationDialog`): Übernahme historischer Konten und Saldenverläufe aus OpenDocument-Tabellen.
* **Energie-ODS-Import** (`EnergyOdsImportDialog`): Übernahme historischer Zähler und Ablesungen aus ODS-Dateien.
* **Datenbank-Reset**: Kontrolliertes Zurücksetzen von Datenbeständen nach expliziter Bestätigung im Sicherheitsdialog.

### 7. Systemzustand (`SystemHealthTab`)

Schreibgeschützte Laufzeitdiagnose für den Betrieb:

* Anwendungsversion (`pom.xml` / Build-Metadaten), aktives Spring-Profil und Java-Runtime.
* PostgreSQL-Verbindungsstatus inklusive gemessener DB-Latenz (ms).
* Letzte angewendete Flyway-Migration (`V1`–`V24`).
* Aktuelle Konfiguration des Remote-TCP-Log-Streamings.
