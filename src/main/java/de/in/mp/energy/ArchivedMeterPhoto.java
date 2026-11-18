package de.in.mp.energy;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Projection pairing an archived {@link MeterReadingPhoto} with its reading date and value.
 */
public record ArchivedMeterPhoto(
        MeterReadingPhoto photo,
        LocalDate readingDate,
        BigDecimal readingValue) {

    /**
     * Builds a clean download file name including meter label and reading date.
     */
    public String downloadFileName(String meterName) {
        String prefix = (meterName != null && !meterName.isBlank())
                ? meterName.replaceAll("[^A-Za-z0-9_-]+", "-").replaceAll("^-|-$", "")
                : "meter";
        String datePart = readingDate != null ? readingDate.toString() : "photo";
        String ext = "image/png".equalsIgnoreCase(photo.getMimeType()) ? "png"
                : "image/webp".equalsIgnoreCase(photo.getMimeType()) ? "webp" : "jpg";
        return prefix + "-" + datePart + "." + ext;
    }
}
