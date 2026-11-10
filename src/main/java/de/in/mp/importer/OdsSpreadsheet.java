package de.in.mp.importer;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Minimal read-only access to an OpenDocument Spreadsheet ({@code .ods}).
 * <p>
 * Only {@code content.xml} is parsed. Repeated rows and columns are expanded
 * so that row and column indices match the positions shown in LibreOffice
 * Calc (0-based). Long runs of empty repeated rows (used by Calc to pad a
 * sheet to its maximum size) are skipped without being materialized.
 */
public final class OdsSpreadsheet {

    static final String TABLE_NS = "urn:oasis:names:tc:opendocument:xmlns:table:1.0";
    static final String OFFICE_NS = "urn:oasis:names:tc:opendocument:xmlns:office:1.0";

    private static final String CONTENT_ENTRY = "content.xml";

    private final List<OdsSheet> sheets;

    private OdsSpreadsheet(List<OdsSheet> sheets) {
        this.sheets = sheets;
    }

    public static OdsSpreadsheet read(InputStream odsStream) {
        Document content = readContentXml(odsStream);
        NodeList tables = content.getElementsByTagNameNS(TABLE_NS, "table");
        if (tables.getLength() == 0) {
            throw new IllegalArgumentException("No spreadsheet table found in ODS file");
        }
        List<OdsSheet> sheets = new ArrayList<>(tables.getLength());
        for (int i = 0; i < tables.getLength(); i++) {
            sheets.add(OdsSheet.of((Element) tables.item(i)));
        }
        return new OdsSpreadsheet(sheets);
    }

    public List<OdsSheet> sheets() {
        return sheets;
    }

    public OdsSheet firstSheet() {
        return sheets.get(0);
    }

    /**
     * First sheet that contains a cell with the given text (case-insensitive)
     * in the given column.
     */
    public Optional<OdsSheet> findSheetWithText(int column, String text) {
        return sheets.stream()
                .filter(sheet -> sheet.findRowWithText(column, text).isPresent())
                .findFirst();
    }

    /**
     * First sheet that contains a cell with the given text (case-insensitive)
     * in any column.
     */
    public Optional<OdsSheet> findSheetWithText(String text) {
        return sheets.stream()
                .filter(sheet -> sheet.findCell(text).isPresent())
                .findFirst();
    }

    private static Document readContentXml(InputStream odsStream) {
        try (ZipInputStream zis = new ZipInputStream(odsStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (CONTENT_ENTRY.equals(entry.getName())) {
                    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                    factory.setNamespaceAware(true);
                    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                    DocumentBuilder builder = factory.newDocumentBuilder();
                    return builder.parse(zis);
                }
            }
        } catch (Exception exception) {
            throw new IllegalArgumentException("Failed to read ODS file: " + exception.getMessage(), exception);
        }
        throw new IllegalArgumentException("Invalid ODS file: content.xml not found");
    }

    static boolean isElement(Node node, String localName) {
        return node instanceof Element && localName.equals(node.getLocalName());
    }
}
