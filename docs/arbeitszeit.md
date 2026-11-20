# Arbeitszeit (`/work-time`, optional)

## Kurzzusammenfassung

Die **Arbeitszeiterfassung** ist ein optionales, pro Benutzer freischaltbares Modul zur lückenlosen Dokumentation mehrteiliger Arbeitstage — speziell zugeschnitten auf Berufe mit getrennten Präsenz-, Heim- und Fahrtzeiten (z. B. Lehrkräfte am Gymnasium). Sie rechnet ein Teilzeit-Deputat (z. B. **20 von 27 Unterrichtsstunden**) auf die entsprechende bezahlte Soll-Arbeitszeit herunter (bei `40h` Vollzeit = `29h 38m`/Woche bzw. `5h 56m`/Tag), zählt unbezahlte Mehr-, Abend- und Wochenendarbeit transparent aus, warnt bei Arbeitszeit- oder Ruhezeitverstößen (`> 10h` am Tag, `< 11h` Pause zwischen zwei Tagen) und bleibt strikt auf die berechtigte Person und Administratoren beschränkt.

---

## Aktivierung, Sichtbarkeit & Datenschutz

* **Standardmäßig ausgeblendet**: Solange für keinen Benutzer aktiviert, erscheint der Menüpunkt *Arbeitszeit* in `MainLayout` für niemanden.
* **Freischaltung pro Konto**: Ein Administrator aktiviert das Modul unter *Administration → Benutzer → Bearbeiten* über die Checkbox *„Persönliche Arbeitszeiterfassung aktivieren“* und hinterlegt das Vertragsmodell.
* **Strenge Isolation**:
  * Normale Benutzer (`ROLE_USER`) sehen den Menüpunkt nur dann, wenn die Erfassung für ihr eigenes Konto aktiviert ist, und können **ausschließlich ihre eigenen** Zeiten einsehen und bearbeiten. Andere Benutzer haben keinen Zugriff und sehen keinen Menüpunkt.
  * Administratoren (`ROLE_ADMIN`) sehen den Menüpunkt, sobald mindestens ein Benutzer ein aktiviertes Profil besitzt, und können über eine Personenauswahl alle aktivierten Profile einsehen und pflegen (z. B. Erfassung für eine Familienangehörige).
  * Im Demo-Modus (`ROLE_DEMO`) bleibt die Arbeitszeiterfassung vollständig deaktiviert.
  * Direkte URL-Aufrufe von `/work-time` ohne Berechtigung werden serverseitig in `beforeEnter` abgewiesen und auf die Startseite umgeleitet.

## Vertrags- & Deputatsmodell (`WorkTimeProfile`)

Jedes Profil rechnet ein Unterrichtsdeputat automatisch pro-rata auf Realarbeitszeit um:

| Parameter | Standardwert | Bedeutung |
| --- | --- | --- |
| Vertragliche UE / Woche | `20,00` | Bezahlte Unterrichtsstunden laut Vertrag |
| Vollzeit-Deputat (UE / Woche) | `27,00` | Reguläre Vollzeit-Unterrichtsverpflichtung |
| Vollzeit-Wochenarbeitszeit | `40,00 h` | Referenz-Wochenarbeitszeit bei 100 % |
| Arbeitstage / Woche | `5` | Reguläre Arbeitstage (Mo–Fr); Tage danach gelten als Wochenende |
| Tages-Warnschwelle | `10,00 h` | Warnmarkierung (z. B. nach Arbeitszeitgesetz) |
| Mindestruhezeit | `11,00 h` | Warnschwelle zwischen Arbeitsende am Vortag und erstem Block am Folgetag |

**Rechenbeispiel (`20 / 27` UE bei `40 h`)**:
* Deputatsanteil: `20 / 27 = 74,07 %` (Anzeige `74.1%`)
* Bezahlte Wochen-Sollzeit: `40h × 20 / 27 = 29h 38m` (`1.778 min`)
* Bezahlte Tages-Sollzeit (bei 5 Tagen): `5h 56m` (`356 min`)

## Schnellerfassung mehrerer Blöcke pro Tag

Ein Arbeitstag kann aus beliebig vielen getrennten Zeitblöcken bestehen (z. B. morgens Schule, mittags Rückfahrt, nachmittags Korrektur zu Hause, abends Elternabend oder zweite Fahrt zur Schule):

* **Kategorien (`WorkTimeCategory`)**:
  * `Schule (Präsenz)` (`SCHOOL`, zählt als Arbeitszeit)
  * `Homeoffice / Vorbereitung & Korrektur` (`HOME_OFFICE`, zählt als Arbeitszeit)
  * `Konferenz / Elterngespräch` (`MEETING`, zählt als Arbeitszeit)
  * `Fahrtweg (Pendeln)` (`COMMUTE`, standardmäßig **nicht** als Arbeitszeit gewertet, aber separat als Fahrtanzahl und Pendelzeit ausgewiesen; per Checkbox bei Bedarf überschreibbar)
  * `Sonstiges` (`OTHER`, zählt als Arbeitszeit)
* **Smarte Zeit-Vorschläge**:
  * Auf einem leeren Tag schlägt das Formular `07:45 – 13:15` (`Schule – Präsenz`) vor.
  * Sobald bereits Blöcke erfasst sind, springt der Vorschlag automatisch auf `+15 Minuten` nach Ende des letzten Blocks (Standardkategorie `Homeoffice / Vorbereitung & Korrektur`), sodass ein Folgeteil in Sekunden ergänzt werden kann.
* **Komfortfunktionen**:
  * Tageswechsel über `◀` / `▶`, Datumsfeld sowie Schnellbuttons *Heute* und *Gestern*.
  * **Vorwoche (Schule) übernehmen**: Kopiert Präsenzblöcke von vor genau 7 Tagen in den aktuellen Tag, ohne vorhandene Blöcke zu überlappen.
  * **Überlappungsschutz**: Überschneidende Zeitintervalle am selben Tag werden vor dem Speichern abgefangen (nahtlose Übergänge wie `13:15` → `13:15` sind erlaubt).

## Auswertungen, Gesundheitshinweise & CSV-Export

* **Zeitraumfilter**: *Diese Woche*, *Letzte Woche*, *Dieser Monat*, *Letzter Monat* und *Gesamter Zeitraum*.
* **4 KPI-Karten**:
  1. Erfasste Arbeitszeit vs. bezahltes Vertragssoll & Überstundensaldo.
  2. Aufteilung nach Schule, Homeoffice, Konferenz und Sonstigem.
  3. Belastungszeiten außerhalb der Kernzeit: Arbeit ab `18:00` Uhr, Wochenendarbeit sowie Pendelfahrten/-dauer.
  4. Gesundheits- & Schutzhinweise: Anzahl der Tage über `> 10h` sowie Anzahl unterschrittener Ruhezeiten (`< 11h`) zwischen zwei aufeinanderfolgenden Tagen.
* **Tagesübersicht & CSV-Export**: Klick auf eine Zeile in der Periodentabelle öffnet direkt den betreffenden Tag im Erfassungsformular. Über **CSV-Export** lässt sich der gewählte Zeitraum als Excel-kompatible, `;`-separierte UTF-8-Datei (mit BOM) herunterladen.
