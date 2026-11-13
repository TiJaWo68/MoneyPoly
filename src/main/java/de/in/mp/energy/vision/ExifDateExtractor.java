package de.in.mp.energy.vision;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.Optional;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;

/**
 * Extracts the original capture date from JPEG/HEIC/PNG EXIF metadata so
 * uploaded meter photos automatically carry their true reading date.
 */
public final class ExifDateExtractor {

    private ExifDateExtractor() {
    }

    public static Optional<LocalDate> extractCaptureDate(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            return Optional.empty();
        }
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(imageBytes));
            ExifSubIFDDirectory directory = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            if (directory == null) {
                return Optional.empty();
            }
            Date original = directory.getDateOriginal();
            if (original == null) {
                return Optional.empty();
            }
            return Optional.of(original.toInstant().atZone(ZoneId.systemDefault()).toLocalDate());
        } catch (Exception exception) {
            return Optional.empty();
        }
    }
}
