package de.in.mp.importer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * A single sheet of an {@link OdsSpreadsheet} with index-based cell access.
 */
public final class OdsSheet {

    /** Upper bound of expanded columns per row; sufficient for all supported layouts. */
    public static final int MAX_COLUMNS = 64;

    private static final int MAX_MATERIALIZED_ROW_REPEAT = 1000;
    private static final DateTimeFormatter GERMAN_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final String name;
    private final Map<Integer, List<Element>> rows;
    private final int rowCount;

    private OdsSheet(String name, Map<Integer, List<Element>> rows, int rowCount) {
        this.name = name;
        this.rows = rows;
        this.rowCount = rowCount;
    }

    static OdsSheet of(Element table) {
        String name = table.getAttributeNS(OdsSpreadsheet.TABLE_NS, "name");
        Map<Integer, List<Element>> rows = new HashMap<>();
        int rowIndex = 0;
        for (Element rowElement : collectRows(table)) {
            int repeat = parseRepeat(rowElement.getAttributeNS(OdsSpreadsheet.TABLE_NS, "number-rows-repeated"));
            List<Element> cells = expandCells(rowElement);
            if (hasContent(cells)) {
                int materialized = Math.min(repeat, MAX_MATERIALIZED_ROW_REPEAT);
                for (int r = 0; r < materialized; r++) {
                    rows.put(rowIndex + r, cells);
                }
            }
            rowIndex += repeat;
        }
        int lastContentRow = rows.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
        return new OdsSheet(name, rows, lastContentRow + 1);
    }

    public String name() {
        return name;
    }

    /** Number of rows up to and including the last row with content. */
    public int rowCount() {
        return rowCount;
    }

    public String text(int row, int column) {
        Element cell = cell(row, column);
        if (cell == null) {
            return "";
        }
        String text = cell.getTextContent();
        return text == null ? "" : text.trim();
    }

    public BigDecimal number(int row, int column) {
        Element cell = cell(row, column);
        if (cell == null) {
            return null;
        }
        String value = cell.getAttributeNS(OdsSpreadsheet.OFFICE_NS, "value");
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    public LocalDate date(int row, int column) {
        Element cell = cell(row, column);
        if (cell == null) {
            return null;
        }
        String dateValue = cell.getAttributeNS(OdsSpreadsheet.OFFICE_NS, "date-value");
        if (dateValue != null && dateValue.length() >= 10) {
            return LocalDate.parse(dateValue.substring(0, 10));
        }
        String text = text(row, column);
        if (text.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(text, GERMAN_DATE);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    public Optional<Integer> findRowWithText(int column, String text) {
        for (int row = 0; row < rowCount; row++) {
            if (rows.containsKey(row) && text.equalsIgnoreCase(text(row, column))) {
                return Optional.of(row);
            }
        }
        return Optional.empty();
    }

    /**
     * Position of the first cell whose text equals {@code text} (case-insensitive).
     */
    public Optional<CellPosition> findCell(String text) {
        for (int row = 0; row < rowCount; row++) {
            if (!rows.containsKey(row)) {
                continue;
            }
            for (int column = 0; column < MAX_COLUMNS; column++) {
                if (text.equalsIgnoreCase(text(row, column))) {
                    return Optional.of(new CellPosition(row, column));
                }
            }
        }
        return Optional.empty();
    }

    public Optional<Integer> findColumnInRow(int row, String text) {
        for (int column = 0; column < MAX_COLUMNS; column++) {
            if (text.equalsIgnoreCase(text(row, column))) {
                return Optional.of(column);
            }
        }
        return Optional.empty();
    }

    private Element cell(int row, int column) {
        List<Element> cells = rows.get(row);
        if (cells == null || column < 0 || column >= cells.size()) {
            return null;
        }
        return cells.get(column);
    }

    private static List<Element> collectRows(Element table) {
        List<Element> result = new ArrayList<>();
        collectRows(table, result);
        return result;
    }

    /** Rows may be nested in row groups or header-row containers. */
    private static void collectRows(Element parent, List<Element> result) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (OdsSpreadsheet.isElement(child, "table-row")) {
                result.add((Element) child);
            } else if (OdsSpreadsheet.isElement(child, "table-row-group")
                    || OdsSpreadsheet.isElement(child, "table-header-rows")
                    || OdsSpreadsheet.isElement(child, "table-rows")) {
                collectRows((Element) child, result);
            }
        }
    }

    private static List<Element> expandCells(Element rowElement) {
        List<Element> expanded = new ArrayList<>();
        NodeList children = rowElement.getChildNodes();
        for (int i = 0; i < children.getLength() && expanded.size() < MAX_COLUMNS; i++) {
            Node child = children.item(i);
            if (!OdsSpreadsheet.isElement(child, "table-cell")
                    && !OdsSpreadsheet.isElement(child, "covered-table-cell")) {
                continue;
            }
            Element cell = (Element) child;
            int repeat = parseRepeat(cell.getAttributeNS(OdsSpreadsheet.TABLE_NS, "number-columns-repeated"));
            for (int r = 0; r < repeat && expanded.size() < MAX_COLUMNS; r++) {
                expanded.add(cell);
            }
        }
        return expanded;
    }

    private static boolean hasContent(List<Element> cells) {
        for (Element cell : cells) {
            String valueType = cell.getAttributeNS(OdsSpreadsheet.OFFICE_NS, "value-type");
            if (valueType != null && !valueType.isBlank()) {
                return true;
            }
            String text = cell.getTextContent();
            if (text != null && !text.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static int parseRepeat(String attribute) {
        if (attribute == null || attribute.isBlank()) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(attribute.trim()));
        } catch (NumberFormatException exception) {
            return 1;
        }
    }

    public record CellPosition(int row, int column) {
    }
}
