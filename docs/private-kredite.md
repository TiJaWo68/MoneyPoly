# Private-Kredite (`/loans`)

## Kurzzusammenfassung

Der Bereich **Private-Kredite** verwaltet Darlehensverträge im Familien- und Freundeskreis inklusive Auszahlungsbetrag, Verzinsung, Ratenplan, Sondertilgungen und laufend aktualisierter Restforderung. Er ist nahtlos mit dem Dashboard (Summe offener Forderungen) und dem Finanzen-Verlauf (optional als Kredit-Linie oder verknüpftes Vermögensereignis) verzahnt und bietet mit der Rolle `ROLE_BORROWER` eine gezielt abgeschottete Nur-Lese-Sicht für Darlehensnehmer.

---

## Funktionsumfang

* **KPI-Übersicht**: Verliehener Gesamtbetrag, bereits zurückgezahlte Summe, aktuell offene Restforderung sowie Anzahl aktiver Verträge.
* **Vertragsverwaltung (`LoanContract`)**:
  * Anlage und Bearbeitung von Verträgen zwischen Kreditgeber und Kreditnehmer (Verknüpfung mit angelegten *Personen*).
  * Erfassung von Darlehensbetrag, Zinssatz, Vertragsdatum, vereinbarter Rate und Notizen.
* **Tilgungs- & Rückzahlungsbuchungen (`LoanRepayment`)**:
  * Erfassung regulärer Raten sowie außerplanmäßiger Sondertilgungen direkt am Vertrag.
  * Automatische Berechnung der Restschuld und visueller Fortschrittsbalken pro Vertrag.
* **Verknüpfung mit Finanzen**:
  * Offene Privatkredite fließen in das Gesamt-Nettovermögen des Dashboards ein und lassen sich im Verlaufschart unter *Finanzen* darstellen sowie mit Vermögensereignissen verknüpfen.

## Berechtigungs- & Rollenkonzept

* **Route**: `/loans` (`@PermitAll`, erreichbar über den Menüpunkt *Private-Kredite*).
* **`ROLE_ADMIN` / `ROLE_USER`**: Dürfen Verträge anlegen, bearbeiten und Rückzahlungen erfassen (außer in eingeschränkten Haushalten ohne Schreibrecht).
* **`ROLE_BORROWER` (Darlehensnehmer)**:
  * Kann sich anmelden und seinen laufenden Kreditstatus einsehen, ohne neue Verträge oder Rückzahlungen anzulegen oder zu verändern (`canModify() == false`).
  * Sieht die eigenen/freigegebenen Darlehen, während Finanz- und Energietabs je nach Freigabe ausgeblendet bleiben.
