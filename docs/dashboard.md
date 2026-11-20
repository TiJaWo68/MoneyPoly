# Dashboard (`/`)

## Kurzzusammenfassung

Das **Dashboard** ist die zentrale Einstiegsseite von MoneyPoly und bündelt die wichtigsten Kennzahlen eines Haushalts auf einen Blick: Gesamt-Nettovermögen, liquide Geldmittel, investiertes Depotvermögen inklusive Performance sowie offene Privatkredite. Mit einem Klick lässt sich über Marktdaten (Yahoo Finance) eine Aktualisierung aller aktiven Depotpositionen anstoßen. Für reine Darlehensnehmer (`ROLE_BORROWER`) werden Finanz- und Investmentbereiche automatisch ausgeblendet.

---

## Aufbau und Kennzahlen

### KPI-Kopfbereich

Im oberen Bereich zeigt das Dashboard bis zu vier responsive KPI-Karten (mit tabellarischer Zifferndarstellung):

* **Nettovermögen**: Summe aus Liquidität, Investments und offenen Privatkrediten.
* **Liquidität**: Summe aller aktiven liquiden Konten (z. B. Giro- und Tagesgeldkonten).
* **Investments**: Aktueller Gesamtwert aller aktiven Depot-/Wertpapierkonten.
* **Privatkredite**: Offene Restforderungen aus privaten Kreditverträgen.

Für Benutzer ohne Finanzzugriff (z. B. `ROLE_BORROWER`) blendet das Dashboard sowohl die Finanz-KPI-Karten als auch die Blöcke für Liquidität und Investments aus und zeigt ausschließlich die relevanten Kreditinformationen.

### Detailblöcke und Drilldowns

Unterhalb der KPI-Karten gliedert sich das Dashboard in drei übersichtliche Blöcke mit Summen- und Fortschrittsbalken im Kopfbereich:

1. **Liquidität**:
   * Auflistung aller aktiven liquiden Konten mit Inhaber-Zuordnung, aktuellem Saldo und optionaler Zinssatz-Anzeige.
   * **Zinswarnung**: Unterschreitet ein Tagesgeldkonto die in *Administration → Einstellungen* konfigurierte Warnschwelle oder läuft ein Aktionszins aus, wird der Zinssatz visuell hervorgehoben.
2. **Investments / Depots**:
   * Auflistung aller aktiven Investment- und Depotkonten mit aktuellem Marktwert bzw. Stichtagssaldo.
   * Sofern eingezahltes Kapital oder Depotpositionen hinterlegt sind, wird zusätzlich die Performance (absoluter Gewinn/Verlust und prozentuale Rendite) angezeigt.
   * **Kurs-Aktualisierung**: Über den Button *„Kurse aller aktiven Depots über Marktdaten aktualisieren“* können aktuelle Kurse per Yahoo Finance abgerufen und die Depotbewertung aktualisiert werden.
3. **Privatkredite**:
   * Kompakte Übersicht laufender Privatkredit-Verträge inklusive Rückzahlungsfortschritt und Restforderung.
   * Klicks auf Konten oder Kredite führen direkt in die jeweilige Detailansicht (`/finance` bzw. `/loans`).

## Mobile Responsivität

Das Dashboard passt seine Spaltenbreiten und Layouts dynamisch an die Fensterbreite an (`< 700 px`):

* Auf Smartphones werden schmalere Betrags-, Zins- und Performance-Spalten genutzt und die Performance zweizeilig gestapelt, damit Kontonamen maximal lesbar bleiben.
* Bei sehr niedrigen Querformat-Displays (`max-height: 500px`) werden zweitrangige Header-/KPI-Elemente ausgeblendet, sodass die Tabellen maximalen Platz erhalten.

## Sichtbarkeit & Rollen

* **Route**: `/` (`@PermitAll`, erreichbar über den Menüpunkt *Dashboard*).
* **`ROLE_ADMIN` / `ROLE_USER` / `ROLE_DEMO`**: Volle Haushaltsübersicht (bei `ROLE_DEMO` auf die Demo-Daten begrenzt).
* **`ROLE_BORROWER`**: Zeigt ausschließlich den Bereich *Privatkredite*.
