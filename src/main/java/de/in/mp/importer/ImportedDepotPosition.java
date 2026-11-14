package de.in.mp.importer;

import java.math.BigDecimal;

/**
 * Single security line extracted from a broker portfolio overview PDF
 * (e.g. Trade Republic {@code VERMÖGENSÜBERSICHT}).
 */
public record ImportedDepotPosition(
        String name,
        String isin,
        BigDecimal quantity,
        BigDecimal unitPriceEur,
        BigDecimal marketValueEur) {
}
