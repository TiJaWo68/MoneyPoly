package de.in.mp.importer;

import java.util.List;

/**
 * SPI for extracting {@link ImportedDepotPosition} holdings from broker
 * portfolio overview PDFs (e.g. Trade Republic {@code VERMÖGENSÜBERSICHT},
 * Scalable Capital {@code Finanzstatus}).
 */
public interface DepotPositionPdfParser {

    /**
     * Returns {@code true} if this parser recognizes the extracted PDF text format.
     */
    boolean supports(String rawText);

    /**
     * Parses extracted PDF text into normalized brokerage holdings.
     */
    List<ImportedDepotPosition> parse(String rawText);
}
