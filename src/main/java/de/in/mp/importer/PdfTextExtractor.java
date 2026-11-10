package de.in.mp.importer;

import java.io.IOException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * Extracts the plain text of a PDF document with Apache PDFBox.
 */
@Component
public class PdfTextExtractor {

    public String extract(byte[] pdfBytes) {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            return new PDFTextStripper().getText(document);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to read PDF: " + exception.getMessage(), exception);
        }
    }
}
