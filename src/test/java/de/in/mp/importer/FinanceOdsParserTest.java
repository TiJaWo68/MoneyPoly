package de.in.mp.importer;

import static de.in.mp.importer.OdsTestFiles.date;
import static de.in.mp.importer.OdsTestFiles.empty;
import static de.in.mp.importer.OdsTestFiles.number;
import static de.in.mp.importer.OdsTestFiles.ods;
import static de.in.mp.importer.OdsTestFiles.row;
import static de.in.mp.importer.OdsTestFiles.table;
import static de.in.mp.importer.OdsTestFiles.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.in.mp.finance.AccountSnapshotService;
import de.in.mp.finance.AccountType;
import de.in.mp.finance.SnapshotValue;

/**
 * Tests the legacy finance spreadsheet parser with a synthetic spreadsheet
 * that mirrors the two supported sheet layouts.
 */
class FinanceOdsParserTest {

    /**
     * Balance matrix: totals in columns A..D, accounts from column E on.
     * "Bank A" groups Giro + Tagesgeld, "Broker" has one account, "Old Bank"
     * stopped reporting after the first date. The row without a date holds a
     * scratch value and must be ignored.
     */
    private static String balanceMatrix() {
        return table("Balances",
                row(number("2"), empty(2), number("10")),
                row(empty(1), text("Datum"), text("Gesamt"), text("Änderung"),
                        text("Bank A"), empty(1), text("Broker"), text("Old Bank")),
                row(empty(4), text("Giro"), text("Tagesgeld"), text("Depot"), text("Spar")),
                row(empty(4), number("999"), number("999")),
                row(number("1"), date("2026-10-31"), number("5000"), empty(1),
                        number("1000.50"), number("500"), number("3499.50")),
                row(number("1"), date("2026-09-30"), number("4800"), empty(1),
                        number("900"), number("400"), number("3400"), number("100")));
    }

    /**
     * Monthly overview: months on the first day, flows and two balance columns
     * plus a computed total column.
     */
    private static String monthlyOverview() {
        return table("Bank A",
                row(text("#"), text("Monat"), empty(3), text("Ausgaben"), empty(1), text("Einnahmen")),
                row(empty(3), text("Ausgaben"), empty(1), text("bereinigte Ausgaben"), empty(1),
                        text("Einnahmen"), empty(1), text("Bank A Giro"), empty(1),
                        text("Bank A Tagesgeld"), empty(1), text("Gesamt")),
                row(empty(1), date("2026-11-01")),
                row(number("1"), date("2026-10-01"), empty(3), number("-300"), empty(1), number("400.50"),
                        empty(1), number("1000.50"), empty(1), number("500"), empty(1), number("1500.50")),
                row(number("1"), date("2026-09-01"), empty(3), number("-250"), empty(1), number("300"),
                        empty(1), number("900"), empty(1), number("400"), empty(1), number("1300")));
    }

    private static InputStream spreadsheet() {
        return ods(balanceMatrix() + monthlyOverview()
                + table("'file:///external.ods'#Bank A", row(number("1"))));
    }

    private static OdsAccountColumn bySource(List<OdsAccountColumn> columns, String label) {
        return columns.stream().filter(c -> c.sourceLabel().equals(label)).findFirst().orElseThrow();
    }

    @Test
    void parsesBalanceMatrixColumnsWithInstitutionAndSubHeaders() {
        List<OdsAccountColumn> columns = FinanceOdsParser.parse(spreadsheet());
        List<OdsAccountColumn> matrix = columns.stream().filter(c -> c.sheetName().equals("Balances")).toList();

        assertThat(matrix).extracting(OdsAccountColumn::sourceLabel)
                .containsExactly("Bank A / Giro", "Bank A / Tagesgeld", "Broker / Depot", "Old Bank / Spar");

        OdsAccountColumn giro = matrix.get(0);
        assertThat(giro.values()).hasSize(2);
        assertThat(giro.latestDate()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(giro.latestBalance()).isEqualByComparingTo("1000.50");
        assertThat(giro.getAccountType()).isEqualTo(AccountType.CHECKING);
        assertThat(giro.getInstitutionName()).isEqualTo("Bank A");
        assertThat(giro.getAccountName()).isEqualTo("Giro");
        assertThat(giro.grouped()).isTrue();
        assertThat(giro.isActive()).isTrue();

        assertThat(matrix.get(1).getAccountType()).isEqualTo(AccountType.SAVINGS);
        assertThat(matrix.get(2).getAccountType()).isEqualTo(AccountType.INVESTMENT);

        OdsAccountColumn oldBank = matrix.get(3);
        assertThat(oldBank.isActive()).isFalse();
        assertThat(oldBank.values()).hasSize(1);
    }

    @Test
    void parsesMonthlyOverviewWithMonthEndDatesAndFlowsOnFirstBalanceColumn() {
        List<OdsAccountColumn> columns = FinanceOdsParser.parse(spreadsheet());
        List<OdsAccountColumn> overview = columns.stream().filter(c -> c.sheetName().equals("Bank A")).toList();

        assertThat(overview).extracting(OdsAccountColumn::sourceLabel)
                .containsExactly("Bank A Giro", "Bank A Tagesgeld", "Gesamt");

        OdsAccountColumn giro = overview.get(0);
        SnapshotValue october = giro.values().get(0);
        assertThat(october.date()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(october.balance()).isEqualByComparingTo("1000.50");
        assertThat(october.inflow()).isEqualByComparingTo("400.50");
        assertThat(october.outflow()).isEqualByComparingTo("300");
        assertThat(giro.values()).extracting(SnapshotValue::date)
                .containsExactly(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 9, 30));

        assertThat(overview.get(1).values()).allMatch(v -> v.inflow() == null && v.outflow() == null);
        assertThat(overview.get(2).isInclude()).isFalse();
    }

    @Test
    void consistencyRuleHoldsForSyntheticMonthlyOverview() {
        OdsAccountColumn giro = bySource(FinanceOdsParser.parse(spreadsheet()), "Bank A Giro");
        SnapshotValue september = giro.values().get(1);
        SnapshotValue october = giro.values().get(0);

        assertThat(AccountSnapshotService.expectedBalance(september.balance(), october.inflow(), october.outflow()))
                .isEqualByComparingTo(october.balance());
    }

    @Test
    void suggestsMergingOverviewColumnsIntoMatchingMatrixAccounts() {
        List<OdsAccountColumn> columns = FinanceOdsParser.parse(spreadsheet());

        assertThat(bySource(columns, "Bank A Giro").getMergeInto()).isSameAs(bySource(columns, "Bank A / Giro"));
        assertThat(bySource(columns, "Bank A Tagesgeld").getMergeInto())
                .isSameAs(bySource(columns, "Bank A / Tagesgeld"));
        assertThat(bySource(columns, "Bank A / Giro").getMergeInto()).isNull();
    }

    @Test
    void ignoresExternalLinkSheets() {
        assertThat(FinanceOdsParser.parse(spreadsheet()))
                .noneMatch(column -> column.sheetName().startsWith("'file:"));
    }

    @Test
    void rejectsSpreadsheetWithoutSupportedSheet() {
        assertThatThrownBy(() -> FinanceOdsParser.parse(ods(table("Other", row(text("x"))))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void extractsAccountHoldersFromInstitutionHeadersAndPersonSubHeaders() {
        String sheet = table("Balances",
                row(empty(1), text("Datum"), text("Gesamt"),
                        text("C24 Birgit"), empty(1),
                        text("C24 Till"),
                        text("Trade Republic"), empty(1),
                        text("VW Bank")),
                row(empty(3),
                        text("Giro"), text("Tagesgeld"),
                        text("Giro"),
                        text("Till"), text("Birgit"),
                        text("Birgit")),
                row(number("1"), date("2026-10-31"), number("10000"),
                        number("1000"), number("500"),
                        number("2000"),
                        number("3000"), number("2500"),
                        number("1000")));

        List<OdsAccountColumn> columns = FinanceOdsParser.parse(ods(sheet));

        assertThat(columns).hasSize(6);

        OdsAccountColumn c24BirgitGiro = bySource(columns, "C24 Birgit / Giro");
        assertThat(c24BirgitGiro.getInstitutionName()).isEqualTo("C24");
        assertThat(c24BirgitGiro.getAccountHolder()).isEqualTo("Birgit");
        assertThat(c24BirgitGiro.getAccountType()).isEqualTo(AccountType.CHECKING);

        OdsAccountColumn c24BirgitSavings = bySource(columns, "C24 Birgit / Tagesgeld");
        assertThat(c24BirgitSavings.getInstitutionName()).isEqualTo("C24");
        assertThat(c24BirgitSavings.getAccountHolder()).isEqualTo("Birgit");
        assertThat(c24BirgitSavings.getAccountType()).isEqualTo(AccountType.SAVINGS);

        OdsAccountColumn c24TillGiro = bySource(columns, "C24 Till / Giro");
        assertThat(c24TillGiro.getInstitutionName()).isEqualTo("C24");
        assertThat(c24TillGiro.getAccountHolder()).isEqualTo("Till");
        assertThat(c24TillGiro.getAccountType()).isEqualTo(AccountType.CHECKING);

        OdsAccountColumn trTill = bySource(columns, "Trade Republic / Till");
        assertThat(trTill.getInstitutionName()).isEqualTo("Trade Republic");
        assertThat(trTill.getAccountHolder()).isEqualTo("Till");
        assertThat(trTill.getAccountType()).isEqualTo(AccountType.INVESTMENT);
        assertThat(trTill.grouped()).isTrue();

        OdsAccountColumn trBirgit = bySource(columns, "Trade Republic / Birgit");
        assertThat(trBirgit.getInstitutionName()).isEqualTo("Trade Republic");
        assertThat(trBirgit.getAccountHolder()).isEqualTo("Birgit");
        assertThat(trBirgit.getAccountType()).isEqualTo(AccountType.INVESTMENT);

        OdsAccountColumn vwBirgit = bySource(columns, "VW Bank / Birgit");
        assertThat(vwBirgit.getInstitutionName()).isEqualTo("VW Bank");
        assertThat(vwBirgit.getAccountHolder()).isEqualTo("Birgit");
    }
}
