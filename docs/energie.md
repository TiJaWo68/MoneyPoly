# Energie & Amortisation („Black Zero“, `/energy`)

## Kurzzusammenfassung

Der Bereich **Energie** verwaltet beliebig viele Gebäude/Standorte mit Zählern für Strom (Bezug/Einspeisung/PV-Erzeugung/Wallbox), Gas, Wasser und Wärmepumpe. Kernfunktionen sind eine schnelle Stapel-Ablesung, ein **KI-gestützter Foto- & Screenshot-Import** (inklusive Zwischenablage `Strg+V`, EXIF-Datumserkennung, Erkennungshinweisen und Foto-Archiv), die **Amortisationsrechnung („Black Zero“)** für PV-Anlagen, Speicher oder Wallboxen sowie eine automatisierte **PDF-Rechnungsprüfung** inklusive E-Mail-Widerspruchsentwurf.

---

## Multi-Haus-Verwaltung & Zugriffsrechte

* Über `EnergyHouse` lassen sich getrennte Liegenschaften verwalten (z. B. Wohnhaus, Ferienhaus, Mietobjekt).
* In *Administration → Benutzer* werden Benutzern einzelne Häuser mit feingranularen Rollen zugewiesen (`OWNER`, `EDITOR`, `VIEWER`).
* Besitzt ein Benutzer **kein** zugewiesenes Haus, blendet `MainLayout` den Menüpunkt *Energie* für ihn komplett aus. Bei mehreren Häusern erscheint unter *Energie* pro Haus ein eigener Unterbaum.

## Tabs im Bereich Energie

### 1. Zählerstände (`Readings`)

* **Tages- & Stichtagsmatrix**: Stellt Ablesewerte und Verbrauchsdifferenzen (Delta zum Vorwert sowie prozentualer Vergleich zum historischen Durchschnitt `vs. Ø`) kompakt pro Datum und Zähler dar.
* **Stapel-Ablesung**: Schnelle Erfassung aller aktiven Zähler eines Datums in einem Schritt.
* **KI-Foto- & Screenshot-Import** (sofern in *Administration → Einstellungen* ein Vision-Provider konfiguriert ist):
  * Upload mehrerer Fotos oder direktes Einfügen aus der Zwischenablage per **`Strg+V` / `Cmd+V`** (z. B. direkt aus einem Wallbox- oder Wechselrichter-Portal).
  * Automatische **EXIF-Datumserkennung** und Zähler-Zuordnung anhand hinterlegter Erkennungshinweise (`recognitionHint`) am jeweiligen Zähler.
  * Prüftabelle mit Plausibilitätsprüfung (Delta zum letzten Zählerstand, Markierung verdächtiger Sprünge oder Rückläufe) und optionaler Archivierung der Originalfotos am Zählerstand (**Foto-Archiv** mit Download/Vorschau).
* **PDF-Rechnungsprüfung (`UtilityBillVerificationService`)**:
  * Upload einer Versorgerrechnung (aktuell u. a. **LogoEnergie** via `LogoEnergieBillParser`) und Abgleich gegen erfasste Zählerstände und Tarife: Prüfung der Rechengenauigkeit, Verbrauchsabweichung, Abgrenzungszeiträume und vorgeschlagener Monatsabschläge.
  * Bei Abweichungen generiert der integrierte **Widerspruchs-Generator (`UtilityBillComplaintGenerator`)** direkt einen strukturierten, deutsch- oder englischsprachigen E-Mail-Entwurf zum Kopieren.

### 2. Amortisation (`Amortization`)

* Bewertet Investitionsgüter (`EnergyAsset`: z. B. PV-Anlage, Stromspeicher, Wallbox, Wärmepumpe) anhand hinterlegter Investitionskosten, Eigenverbrauchs-/Einspeise-Zähler und gültiger Strom-/Gaststarife.
* Zeigt Gesamtinvestition, bisher amortisierten Betrag, Restbetrag bis zur „Black Zero“ sowie Amortisationsgrad pro Investitionsobjekt.

### 3. Stammdaten (`Master Data`)

* **Investitionsobjekte (`EnergyAsset`)**: Anlagekosten, Inbetriebnahmedatum und Zuordnung zu Zählern.
* **Zähler (`Meter`)**: Typ (`GRID_IMPORT`, `GRID_EXPORT`, `PV_GENERATION`, `WATER`, `GAS`, `WALLBOX_PRIVATE`, `WALLBOX_COMPANY` u. a.), Einheit, Status, Zählerwechsel/Startwert, optionaler KI-Erkennungshinweis sowie optionale Verknüpfung auf eine automatisierte Datenquelle (inkl. Messwert/RFID-Tag und Start-Offset-Kalibrierung).
* **Automatisierte Datenquellen (`EnergyDataSource`)**: Direkter, duplikatsicherer Abruf per Button (*„Geräte-Stände abrufen“*) oder täglichem Cron (`23:55` Uhr) für **Shelly Pro 3EM** (`/rpc/EMData.GetStatus?id=0`), **Huawei FusionSolar Kiosk** (`station-kiosk-file`) und **Kathrein easyOperate Wallbox** (inkl. RFID-Tag-Auswertung und AES-256-GCM-verschlüsselten Zugangsdaten).
* **Tarife (`EnergyTariff`)**: Arbeitspreis, Grundpreis und Gültigkeitszeitraum (`validFrom` – `validTo`), optional Filter zum Anzeigen abgelaufener Tarife.
* **ODS-Import**: Übernahme historischer Zählerstände aus Tabellenkalkulationen (auch im Admin-Bereich unter Datenmigration verfügbar).
