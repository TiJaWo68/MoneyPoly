package de.in.mp.importer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds minimal synthetic {@code .ods} files for parser tests.
 */
final class OdsTestFiles {

    private OdsTestFiles() {
    }

    static InputStream ods(String tablesXml) {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <office:document-content
                    xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                    xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"
                    xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">
                  <office:body><office:spreadsheet>
                """ + tablesXml + """
                  </office:spreadsheet></office:body>
                </office:document-content>
                """;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                zip.putNextEntry(new ZipEntry("content.xml"));
                zip.write(xml.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            return new ByteArrayInputStream(bytes.toByteArray());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    static String text(String value) {
        return "<table:table-cell office:value-type=\"string\"><text:p>" + value + "</text:p></table:table-cell>";
    }

    static String number(String value) {
        return "<table:table-cell office:value-type=\"float\" office:value=\"" + value + "\"/>";
    }

    static String date(String isoDate) {
        return "<table:table-cell office:value-type=\"date\" office:date-value=\"" + isoDate + "\"/>";
    }

    static String empty(int count) {
        return count == 1 ? "<table:table-cell/>"
                : "<table:table-cell table:number-columns-repeated=\"" + count + "\"/>";
    }

    static String row(String... cells) {
        return "<table:table-row>" + String.join("", cells) + "</table:table-row>";
    }

    static String table(String name, String... rows) {
        return "<table:table table:name=\"" + name + "\">" + String.join("", rows)
                + "<table:table-row table:number-rows-repeated=\"1048000\"><table:table-cell/></table:table-row>"
                + "</table:table>";
    }
}
