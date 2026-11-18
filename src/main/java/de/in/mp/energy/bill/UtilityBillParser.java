package de.in.mp.energy.bill;

/**
 * SPI for extracting structured {@link UtilityBill} data from utility invoice text.
 */
public interface UtilityBillParser {

    /**
     * Returns {@code true} if this parser recognizes the extracted PDF text format.
     */
    boolean supports(String extractedText);

    /**
     * Parses extracted PDF text into a {@link UtilityBill}.
     */
    UtilityBill parse(String extractedText);
}
