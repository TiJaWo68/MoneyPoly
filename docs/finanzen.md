# Finanzen (`/finance`)

## Kurzzusammenfassung

Der Bereich **Finanzen** bildet die vollständige Vermögens- und Kontenverwaltung eines oder mehrerer Haushalte ab. Er umfasst einen interaktiven Vermögensverlaufs-Chart (mit Zoom, Pan, Achsenumschaltung und Doppelklick-Annotation für Vermögensereignisse), eine Stichtags-Saldenmatrix, einen hierarchischen Kontenbaum inklusive Tagesgeld-Zinshopping und Depotpositions-Tracking, automatisierte Kontoauszugs- und Tabellenimporte (PDF/ODS), optionalen Read-Only-Bankabruf via **Open-Banking (Enable Banking & YAXI)** sowie einen pro Haushalt aktivierbaren **Ausstiegsplan** für die Ruhestands- und Reichweitenplanung.

---

## Multi-Haushalt & Zugriffsmodell

* Benutzer sind über ihr Konto einem **Haushalt** zugeordnet (`HouseholdService`). Administratoren und berechtigte Benutzer mit Zugriff auf mehrere Haushalte sehen im Menübaum unter *Finanzen* für jeden Haushalt eigene Unterpunkte sowie eine Haushaltsauswahl in der Ansicht.
* Für reine Darlehensnehmer ohne Finanzfreigabe (`hasFinanceAccess == false`, z. B. `ROLE_BORROWER`) bleibt der gesamte Menüpunkt *Finanzen* ausgeblendet.
* Im Demo-Modus (`ROLE_DEMO`) stehen Schreibfunktionen (z. B. Bankverbindungen ändern oder Positionen importieren) im schreibgeschützten bzw. abgegrenzten Modus zur Verfügung.

## Tabs im Bereich Finanzen

### 1. Verlauf (Chart)

Der Tab *Verlauf* (`BalanceChartPanel`) visualisiert die zeitliche Entwicklung aller aktiven Konten als gestapeltes Flächen- und Liniendiagramm (SVG) inklusive optionaler Darstellung offener Privatkredite und Perioden-Deltas:

* **Zeitraumfilter & Navigation**: Schnellfilter (1J, 3J, 5J, 10J, Max), Zeitraum-Schieberegler, Pan nach links/rechts sowie Zoom (Aufziehen per Maus/Touch oder Mausrad).
* **Skalierungsumschaltung**:
  * **Y-Achse**: Wechsel zwischen Nullpunkt-Skalierung (`Ab 0`) und fokussierter Detail-Skalierung (`Fokus`).
  * **X-Achse**: Wechsel zwischen zeitproportionaler Achse (`Zeit`) und gleichmäßigem Punktabstand (`Gleichmäßig`).
* **Vermögensereignisse (`WealthEvent`)**: Ein Doppelklick auf einen Datenpunkt oder eine Strecke öffnet den Dialog zur Erfassung außerordentlicher Zu- oder Abflüsse (z. B. größere Ausgabe, Einmalzahlung oder Verknüpfung mit einem Privatkredit), die direkt als Kontextmarker im Verlauf erscheinen.

### 2. Stichtags-Salden (`Balances`)

Die Stichtags-Matrix stellt jeden Stichtag als Zeile und die Konten als Spalten gegenüber:

* **Schnellerfassung**: Neues Stichtagsdatum mit Salden und optional eingezahltem Kapital erfassen.
* **PDF-Kontoauszugs-Import**: Unterstützt das Einlesen von PDF-Auszügen für **C24**, **Targobank**, **Chase**, **Scalable Capital** (Kontoauszug und Finanzstatus) sowie **Trade Republic** (Cash, Übersicht und Depot). Manuell zugeordnete Kontonamen merkt sich MoneyPoly als Import-Schlüssel für den nächsten Import automatisch.
* **ODS-Import / Backfill**: Einmalige oder historische Übernahme aus OpenDocument-Tabellen (`.ods`).

### 3. Konten (`Accounts`)

Die Kontoverwaltung organisiert Bank- und Wertpapierkonten als Baumstruktur (Bank/Institut → Unterkonten wie Girokonto, Tagesgeld, Depot):

* **Bankverzeichnis**: Integrierte IBAN-/BIC-Abfrage über **OpenIBAN** (`MONEYPOLY_OPENIBAN_BASE_URL`).
* **Tagesgeld-Konditionen & Zinshopping**: Hinterlegen von Aktionszins, Befristungsdatum und Folgezins; unterschreitet der aktuelle Zins die globale Warnschwelle, weist das System darauf hin.
* **Depot-Positionen & Yahoo Finance**: In Depotkonten lassen sich einzelne Wertpapierpositionen (ISIN/Ticker, Stückzahl, Einstand/Kapital) manuell pflegen, per PDF-Depotauszug importieren (`DepotPositionPdfParser`) und über Yahoo Finance mit aktuellen Marktkursen bewerten.

### 4. Ausstiegsplan (optional pro Haushalt)

Sofern beim Haushalt aktiviert (`exit_plan_enabled`, konfigurierbar unter *Administration → Benutzer → Haushalte verwalten*), erscheint der zusätzliche Menü- bzw. Tab-Eintrag **Ausstiegsplan** (`ExitPlanPanel`):

* Simulation der Vermögensreichweite für eine oder zwei Personen (Alter, geplanter Ausstiegszeitpunkt, gesetzliche/private Renten, Überbrückungs-Krankenkassenbeitrag, monatliche Sparrate bis Ausstieg, Liquiditätsreserve, erwartete Rendite).
* Drei Entnahmestrategien (`ExitPlanMode`): **Kapitalerhalt** (`CAPITAL_PRESERVATION`), **Verzehr bis Zielalter** (`DEPLETE_TO_AGE`) und **feste Entnahme** (`FIXED_WITHDRAWAL`).
* Interaktives Jahres-Verlaufsdiagramm (`ExitPlanChartPanel`) sowie jahresweise Detailtabelle.

---

## Open-Banking (PSD2 / AIS)

MoneyPoly unterstützt optional den rein lesenden Bankabruf über zwei Provider:

* **Enable Banking**: Hintergrundfähiger Abruf mit 90–180-Tage-Consent (`MONEYPOLY_ENABLE_BANKING_*`), ideal für den automatischen Nacht-Sync per Cron (`MONEYPOLY_BANKSYNC_CRON`, Standard `03:00` Uhr).
* **YAXI**: Interaktiver Ticket-/Widget-Abruf (`MONEYPOLY_YAXI_API_KEY`), z. B. für Trade Republic.

**Sicherheit & Datenschutz**: Es werden keinerlei Online-Banking-Passwörter oder PINs gespeichert. Erteilte Session-/Consent-Tokens liegen **AES-256-GCM-verschlüsselt** in PostgreSQL (`MONEYPOLY_CRYPTO_KEY`). Abgerufene Salden werden als monatlich bzw. stichtagsbezogen konsolidierte Snapshots abgelegt.

Einrichtung und Verwaltung erfolgen unter *Finanzen → Konten → Konto bearbeiten → „Bankverbindungen verwalten…“* (siehe auch [setup-und-betrieb.md](setup-und-betrieb.md)).
