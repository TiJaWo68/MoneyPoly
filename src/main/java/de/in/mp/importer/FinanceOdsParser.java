package de.in.mp.importer;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import de.in.mp.finance.AccountType;
import de.in.mp.finance.SnapshotValue;

/**
 * Reads the legacy finance spreadsheet used before MoneyPoly.
 * <p>
 * Two sheet layouts are recognized:
 * <ol>
 * <li><b>Balance matrix</b>: a header row containing {@value #DATE_HEADER}
 * (date column), the institution names on that row and the sub-account
 * names on the row below. Institution names apply to all following columns
 * until the next institution. Account columns start at the first column with
 * a sub-account name; columns before it hold computed totals. Every row with
 * a date yields one balance per non-empty cell.</li>
 * <li><b>Monthly overview</b>: a header row containing
 * {@value #INFLOW_HEADER} and {@value #OUTFLOW_HEADER}; the balance columns
 * follow to the right of the inflow column. Dates denote the month and are
 * mapped to the last day of that month. The first balance column receives
 * the monthly inflow and (positive) adjusted outflow.</li>
 * </ol>
 */
public final class FinanceOdsParser {

    static final String DATE_HEADER = "Datum";
    static final String INFLOW_HEADER = "Einnahmen";
    static final String OUTFLOW_HEADER = "bereinigte Ausgaben";
    static final String TOTAL_HEADER = "Gesamt";

    private static final String EXTERNAL_LINK_PREFIX = "'file:";
    private static final int DATE_SEARCH_ROWS = 20;
    private static final List<String> CHECKING_MARKERS = List.of("giro");
    private static final List<String> SAVINGS_MARKERS = List.of("tagesgeld", "pocket", "spar", "festgeld");

    private FinanceOdsParser() {
    }

    public static List<OdsAccountColumn> parse(InputStream odsStream) {
        OdsSpreadsheet spreadsheet = OdsSpreadsheet.read(odsStream);
        List<OdsAccountColumn> columns = new ArrayList<>();

        List<OdsSheet> sheets = spreadsheet.sheets().stream()
                .filter(sheet -> !sheet.name().startsWith(EXTERNAL_LINK_PREFIX))
                .toList();

        Optional<OdsSheet> matrix = sheets.stream()
                .filter(sheet -> sheet.findCell(DATE_HEADER).isPresent())
                .findFirst();
        matrix.ifPresent(sheet -> columns.addAll(parseBalanceMatrix(sheet)));

        sheets.stream()
                .filter(sheet -> sheet.findCell(OUTFLOW_HEADER).isPresent())
                .findFirst()
                .ifPresent(sheet -> columns.addAll(parseMonthlyOverview(sheet)));

        if (columns.isEmpty()) {
            throw new IllegalArgumentException("No supported finance sheet found in ODS file");
        }
        applyDefaults(columns);
        suggestMerges(columns);
        return columns;
    }

    static List<OdsAccountColumn> parseBalanceMatrix(OdsSheet sheet) {
        OdsSheet.CellPosition header = sheet.findCell(DATE_HEADER).orElseThrow();
        int headerRow = header.row();
        int subRow = headerRow + 1;
        int dateColumn = header.column();

        int firstAccountColumn = -1;
        for (int column = dateColumn + 1; column < OdsSheet.MAX_COLUMNS; column++) {
            if (!sheet.text(subRow, column).isEmpty()) {
                firstAccountColumn = column;
                break;
            }
        }
        if (firstAccountColumn < 0) {
            return List.of();
        }

        List<OdsAccountColumn> result = new ArrayList<>();
        String institution = "";
        for (int column = firstAccountColumn; column < OdsSheet.MAX_COLUMNS; column++) {
            String institutionCell = sheet.text(headerRow, column);
            String subCell = sheet.text(subRow, column);
            if (!institutionCell.isEmpty()) {
                institution = institutionCell;
            }
            if (institutionCell.isEmpty() && subCell.isEmpty()) {
                continue;
            }
            List<SnapshotValue> values = new ArrayList<>();
            for (int row = subRow + 1; row < sheet.rowCount(); row++) {
                LocalDate date = sheet.date(row, dateColumn);
                BigDecimal balance = sheet.number(row, column);
                if (date != null && balance != null) {
                    values.add(SnapshotValue.ofBalance(date, balance));
                }
            }
            if (!values.isEmpty()) {
                result.add(new OdsAccountColumn(sheet.name(), column, institution, subCell, values));
            }
        }
        return result;
    }

    static List<OdsAccountColumn> parseMonthlyOverview(OdsSheet sheet) {
        OdsSheet.CellPosition outflowHeader = sheet.findCell(OUTFLOW_HEADER).orElseThrow();
        int headerRow = outflowHeader.row();
        int outflowColumn = outflowHeader.column();
        Optional<Integer> inflowColumn = sheet.findColumnInRow(headerRow, INFLOW_HEADER);
        if (inflowColumn.isEmpty()) {
            return List.of();
        }
        int dateColumn = findDateColumn(sheet, headerRow, outflowColumn);
        if (dateColumn < 0) {
            return List.of();
        }

        List<OdsAccountColumn> result = new ArrayList<>();
        boolean firstBalanceColumn = true;
        for (int column = inflowColumn.get() + 1; column < OdsSheet.MAX_COLUMNS; column++) {
            String headerText = sheet.text(headerRow, column);
            if (headerText.isEmpty()) {
                continue;
            }
            List<SnapshotValue> values = new ArrayList<>();
            for (int row = headerRow + 1; row < sheet.rowCount(); row++) {
                LocalDate month = sheet.date(row, dateColumn);
                BigDecimal balance = sheet.number(row, column);
                if (month == null || balance == null) {
                    continue;
                }
                LocalDate monthEnd = month.withDayOfMonth(month.lengthOfMonth());
                if (firstBalanceColumn) {
                    values.add(new SnapshotValue(monthEnd, balance, abs(sheet.number(row, inflowColumn.get())),
                            abs(sheet.number(row, outflowColumn)), null));
                } else {
                    values.add(SnapshotValue.ofBalance(monthEnd, balance));
                }
            }
            if (!values.isEmpty()) {
                OdsAccountColumn accountColumn = new OdsAccountColumn(sheet.name(), column, "", headerText, values);
                accountColumn.setInclude(!TOTAL_HEADER.equalsIgnoreCase(headerText));
                result.add(accountColumn);
                firstBalanceColumn = false;
            }
        }
        return result;
    }

    private static int findDateColumn(OdsSheet sheet, int headerRow, int maxColumn) {
        for (int row = headerRow + 1; row < Math.min(sheet.rowCount(), headerRow + 1 + DATE_SEARCH_ROWS); row++) {
            for (int column = 0; column < maxColumn; column++) {
                if (sheet.date(row, column) != null) {
                    return column;
                }
            }
        }
        return -1;
    }

    /**
     * Pre-fills the editable settings: names from the headers, account type
     * from the sub-account name and the active flag from whether the column
     * has a value on the newest date of its sheet.
     */
    static void applyDefaults(List<OdsAccountColumn> columns) {
        for (OdsAccountColumn column : columns) {
            String name = column.subHeader().isEmpty() ? column.institutionHeader() : column.subHeader();
            column.setInstitutionName(column.institutionHeader().isEmpty() ? null : column.institutionHeader());
            column.setAccountName(name);
            column.setBankName(column.institutionHeader().isEmpty() ? column.sheetName() : column.institutionHeader());
            column.setAccountType(suggestType(column.subHeader()));

            LocalDate newestOfSheet = columns.stream()
                    .filter(other -> other.sheetName().equals(column.sheetName()))
                    .map(OdsAccountColumn::latestDate)
                    .max(LocalDate::compareTo)
                    .orElse(null);
            column.setActive(newestOfSheet != null && newestOfSheet.equals(column.latestDate()));
        }
    }

    /**
     * Suggests merging monthly-overview columns into a balance-matrix column
     * whose institution is a prefix of the overview sheet name and whose
     * sub-account words all appear in the overview header.
     */
    static void suggestMerges(List<OdsAccountColumn> columns) {
        List<OdsAccountColumn> matrixColumns = columns.stream()
                .filter(column -> !column.institutionHeader().isEmpty())
                .toList();
        for (OdsAccountColumn overview : columns) {
            if (!overview.institutionHeader().isEmpty()) {
                continue;
            }
            String sheet = overview.sheetName().toLowerCase(Locale.ROOT);
            String header = overview.subHeader().toLowerCase(Locale.ROOT);
            matrixColumns.stream()
                    .filter(candidate -> sheet.startsWith(candidate.institutionHeader().toLowerCase(Locale.ROOT)))
                    .filter(candidate -> !candidate.subHeader().isEmpty()
                            && allWordsContained(candidate.subHeader(), header))
                    .findFirst()
                    .ifPresent(overview::setMergeInto);
        }
    }

    private static boolean allWordsContained(String words, String target) {
        return Arrays.stream(words.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(word -> !word.isEmpty())
                .allMatch(target::contains);
    }

    static AccountType suggestType(String label) {
        String lower = label == null ? "" : label.toLowerCase(Locale.ROOT);
        if (CHECKING_MARKERS.stream().anyMatch(lower::contains)) {
            return AccountType.CHECKING;
        }
        if (SAVINGS_MARKERS.stream().anyMatch(lower::contains)) {
            return AccountType.SAVINGS;
        }
        return AccountType.INVESTMENT;
    }

    private static BigDecimal abs(BigDecimal value) {
        return value == null ? null : value.abs();
    }
}
