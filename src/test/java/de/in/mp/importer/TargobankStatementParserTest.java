package de.in.mp.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.in.mp.finance.AccountType;

/**
 * Tests the Targobank "Finanzstatus" parser with synthetic statement text
 * (fictional holder, account numbers and amounts) in both overview layouts.
 */
class TargobankStatementParserTest {

    private static final String CURRENT_LAYOUT = """
            TARGOBANK AG | Vorstand: Beispiel
            Herr
            Max Mustermann
            Musterweg 1
            12345 Musterstadt
            F I N A N Z S T A T U S vom 01.10.2026 - 31.10.2026
            Girokonten
            Kontotyp Kontonummer Währung Guthaben/Kredit______________________________
            BEST-KONTO 1111111111 EUR 2.500,00______________________________
            Summe EUR 2.500,00
            Geldanlage & Investments
            Kontotyp Kontonummer Währung Sparguthaben______________________________
            DOPPELZINS-PLAN-SPARBUCH
            Zinssatz p.a.:
            0,01%
            2222222222 EUR 1,00
            EUR TAGESGELDKONTO
            Zinssatz p.a.:
            0,60%
            3333333333 EUR 100,00
            ______________________________
            Summe Geldanlagen & Investments EUR 101,00______________________________
            Gesamtguthaben EUR 2.601,00
            Monatsübersicht
            BEST-KONTO                              1111111111 EUR
            IBAN: DE12 3456 7890 1111 1111 11                   BIC CODE: TESTDEXX
            30.09. MI ANFANGSSALDO 2.000,00
            IBAN: DE99999999990000000001
            31.10. SA ENDSALDO 2.500,00______________________________
            Summe 1.500,00 2.000,00
            EUR TAGESGELDKONTO                      3333333333 EUR
            IBAN: DE12 3456 7890 3333 3333 33                   BIC CODE: TESTDEXX
            01.10. DO ANFANGSSALDO 100,00
            31.10. SA ENDSALDO 100,00
            Summe 0,00 0,00
            """;

    private static final String LEGACY_LAYOUT = """
            Herr
            Max Mustermann
            Ihre TARGOBANK
            F I N A N Z S T A T U S vom 01.11.2010 - 30.11.2010
            Girokonten
            Kontotyp Kontonummer Währung Guthaben/Kredit
            1.234,561111111111 EURBEST-KONTO
            Summe EUR 1.234,56
            Geldanlage & Investments
            Kontotyp Zahlungsverkehrsnummer Währung Sparguthaben
            9.876,542222222222 EURDOPPELZINS SPARBUCH
            Gesamtguthaben EUR 11.111,10
            BEST-KONTO 1111111111 EUR
            IBAN: DE12 3456 7890 1111 1111 11 BIC CODE: TESTDEXX
            30.11 DI 1.234,56ENDSALDO
            Summe 400,00 600,00
            """;

    private final TargobankStatementParser parser = new TargobankStatementParser();

    @Test
    void supportsFinanzstatusOnly() {
        assertThat(parser.supports(CURRENT_LAYOUT)).isTrue();
        assertThat(parser.supports("TARGOBANK Werbung")).isFalse();
    }

    @Test
    void parsesAllAccountsOfCurrentLayout() {
        List<StatementEntry> entries = parser.parse(CURRENT_LAYOUT);

        assertThat(entries).hasSize(3);

        StatementEntry checking = entries.get(0);
        assertThat(checking.accountLabel()).isEqualTo("BEST-KONTO");
        assertThat(checking.suggestedType()).isEqualTo(AccountType.CHECKING);
        assertThat(checking.accountNumber()).isEqualTo("1111111111");
        assertThat(checking.iban()).isEqualTo("DE12345678901111111111");
        assertThat(checking.accountHolder()).isEqualTo("Max Mustermann");
        assertThat(checking.closingDate()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(checking.closingBalance()).isEqualByComparingTo("2500.00");
        assertThat(checking.outflow()).isEqualByComparingTo("1500.00");
        assertThat(checking.inflow()).isEqualByComparingTo("2000.00");

        StatementEntry savingsBook = entries.get(1);
        assertThat(savingsBook.accountLabel()).isEqualTo("DOPPELZINS-PLAN-SPARBUCH");
        assertThat(savingsBook.suggestedType()).isEqualTo(AccountType.SAVINGS);
        assertThat(savingsBook.iban()).isNull();
        assertThat(savingsBook.closingBalance()).isEqualByComparingTo("1.00");
        assertThat(savingsBook.inflow()).isNull();
        assertThat(savingsBook.importKey()).isEqualTo("Targobank|2222222222");

        StatementEntry callMoney = entries.get(2);
        assertThat(callMoney.accountLabel()).isEqualTo("EUR TAGESGELDKONTO");
        assertThat(callMoney.iban()).isEqualTo("DE12345678903333333333");
        assertThat(callMoney.closingBalance()).isEqualByComparingTo("100.00");
        assertThat(callMoney.inflow()).isEqualByComparingTo("0");
    }

    @Test
    void parsesLegacyLayoutWithBalanceInFront() {
        List<StatementEntry> entries = parser.parse(LEGACY_LAYOUT);

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).accountLabel()).isEqualTo("BEST-KONTO");
        assertThat(entries.get(0).closingBalance()).isEqualByComparingTo("1234.56");
        assertThat(entries.get(0).closingDate()).isEqualTo(LocalDate.of(2010, 11, 30));
        assertThat(entries.get(0).outflow()).isEqualByComparingTo("400.00");
        assertThat(entries.get(0).inflow()).isEqualByComparingTo("600.00");
        assertThat(entries.get(1).accountLabel()).isEqualTo("DOPPELZINS SPARBUCH");
        assertThat(entries.get(1).closingBalance()).isEqualByComparingTo("9876.54");
    }
}
