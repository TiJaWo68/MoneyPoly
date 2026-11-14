package de.in.mp.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.in.mp.finance.AccountType;

/**
 * Tests the Chase statement parser with synthetic statement text (fictional
 * holder, address, IBAN and amounts) mirroring the J.P. Morgan SE PDF layout.
 */
class ChaseStatementParserTest {

    private static final String CHASE_SAVINGS_STATEMENT = """
            Max Mustermann
            Musterweg 1
            12345 Musterstadt
            Am schnellsten erreichst du uns im „Hilfe"-Menü
            der Chase App über die Anruf-Funktion oder den Chat.
            Du kannst uns auch an folgende Adresse
            schreiben: J.P. Morgan SE, Postfach 11 03 65,
            10833 Berlin.
            Kontoauszug – Chase Tagesgeld
            Datum: 02. November 2026
            Zeitraum: 28. Oktober 2026 – 31. Oktober 2026
            IBAN: DE02 5011 0808 0000 0000 01
            BIC: CHASDEF2
            Startsaldo: 0,00 €
            Kontogutschriften: +12.345,67 €
            Kontobelastungen: -100,00 €
            Endsaldo: 12.245,67 €
            Transaktionsübersicht
            Datum Beschreibung Betrag Kontostand
            28. Okt. 2026 Von MAX MUSTERMANN
            IBAN: DE02 5002 4024 0000 0000 02
            Sparen
            +12.345,67 € 12.345,67 €
            29. Okt. 2026 An Max Mustermann
            IBAN: DE02 5002 4024 0000 0000 02
            Test
            -100,00 € 12.245,67 €
            Seite 1 von 2
            """;

    private static final String CHASE_CHECKING_SINGLE_DIGIT_DAY = """
            Erika Mustermann
            Beispielallee 4
            54321 Beispielort
            J.P. Morgan SE
            Kontoauszug - Chase Girokonto
            Zeitraum: 1. März 2026 - 31. März 2026
            IBAN: DE02 5011 0808 0000 0000 99
            BIC: CHASDEF2
            Startsaldo: 500,00 €
            Kontogutschriften: +0,00 €
            Kontobelastungen: -50,00 €
            Endsaldo: 450,00 €
            Transaktionsübersicht
            """;

    private final ChaseStatementParser parser = new ChaseStatementParser();

    @Test
    void supportsChaseStatementsOnly() {
        assertThat(parser.supports(CHASE_SAVINGS_STATEMENT)).isTrue();
        assertThat(parser.supports("Scalable Capital Bank GmbH Kontoauszug Verrechnungskonto")).isFalse();
        assertThat(parser.supports(null)).isFalse();
    }

    @Test
    void extractsHeaderIbanNotCounterpartyIbanAndGermanMonthPeriod() {
        List<StatementEntry> entries = parser.parse(CHASE_SAVINGS_STATEMENT);

        assertThat(entries).hasSize(1);
        StatementEntry entry = entries.get(0);
        assertThat(entry.bank()).isEqualTo("Chase");
        assertThat(entry.accountLabel()).isEqualTo("Chase Tagesgeld");
        assertThat(entry.suggestedType()).isEqualTo(AccountType.SAVINGS);
        assertThat(entry.iban()).isEqualTo("DE02501108080000000001");
        assertThat(entry.importKey()).isEqualTo("IBAN|DE02501108080000000001");
        assertThat(entry.accountHolder()).isEqualTo("Max Mustermann");
        assertThat(entry.closingDate()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(entry.closingBalance()).isEqualByComparingTo("12245.67");
        assertThat(entry.inflow()).isEqualByComparingTo("12345.67");
        assertThat(entry.outflow()).isEqualByComparingTo("100.00");
    }

    @Test
    void supportsUmlautMonthsAndCheckingAccounts() {
        List<StatementEntry> entries = parser.parse(CHASE_CHECKING_SINGLE_DIGIT_DAY);

        assertThat(entries).hasSize(1);
        StatementEntry entry = entries.get(0);
        assertThat(entry.accountLabel()).isEqualTo("Chase Girokonto");
        assertThat(entry.suggestedType()).isEqualTo(AccountType.CHECKING);
        assertThat(entry.closingDate()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(entry.closingBalance()).isEqualByComparingTo("450.00");
    }

    @Test
    void rejectsMalformedChaseTextWithoutPeriod() {
        assertThatThrownBy(() -> parser.parse("J.P. Morgan SE Kontoauszug – Chase Tagesgeld"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
