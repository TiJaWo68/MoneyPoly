package de.in.mp.finance;

/**
 * Summary counts returned after synchronizing a depot's holdings against an
 * imported broker overview PDF.
 */
public record DepotImportResult(
        int created,
        int updated,
        int consolidated,
        int deleted,
        int totalImported) {
}
