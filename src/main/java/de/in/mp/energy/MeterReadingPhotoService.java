package de.in.mp.energy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;

/**
 * Stores and retrieves compressed proof photos attached to {@link MeterReading} entries.
 */
@Service
public class MeterReadingPhotoService {

    static final int MAX_DIMENSION_PX = 1280;
    private static final float JPEG_QUALITY = 0.80f;
    private static final int MAX_RAW_FALLBACK_BYTES = 2_000_000;
    private static final int FILE_NAME_MAX_LENGTH = 255;

    private final MeterReadingPhotoRepository repository;

    public MeterReadingPhotoService(MeterReadingPhotoRepository repository) {
        this.repository = repository;
    }

    /**
     * Compresses and archives the uploaded photo for {@code meterReadingId}, replacing
     * any previously stored photo for the same reading.
     */
    @Transactional
    public MeterReadingPhoto savePhoto(
            UUID meterReadingId, String fileName, String originalMimeType, byte[] rawBytes) {
        if (meterReadingId == null) {
            throw new IllegalArgumentException("Meter reading ID is required");
        }
        if (rawBytes == null || rawBytes.length == 0) {
            throw new IllegalArgumentException("Image bytes must not be empty");
        }

        CompressedImage normalized = compressImage(rawBytes, originalMimeType);
        MeterReadingPhoto entity = repository.findByMeterReadingId(meterReadingId)
                .orElseGet(MeterReadingPhoto::new);
        entity.setMeterReadingId(meterReadingId);
        entity.setFileName(normalizeFileName(fileName, normalized.mimeType()));
        entity.setMimeType(normalized.mimeType());
        entity.setImageData(normalized.bytes());
        entity.setCreatedAt(Instant.now());
        return repository.save(entity);
    }

    @Transactional(readOnly = true)
    public Optional<MeterReadingPhoto> findByReadingId(UUID readingId) {
        return readingId == null ? Optional.empty() : repository.findByMeterReadingId(readingId);
    }

    @Transactional(readOnly = true)
    public Set<UUID> findArchivedReadingIds(Collection<UUID> readingIds) {
        if (readingIds == null || readingIds.isEmpty()) {
            return Set.of();
        }
        return repository.findArchivedReadingIds(readingIds);
    }

    @Transactional(readOnly = true)
    public Optional<ArchivedMeterPhoto> findLatestForMeter(UUID meterId) {
        if (meterId == null) {
            return Optional.empty();
        }
        List<ArchivedMeterPhoto> photos = repository.findArchivedByMeterOrderByDateDesc(meterId);
        return photos.isEmpty() ? Optional.empty() : Optional.of(photos.get(0));
    }

    /**
     * Finds the archived photo for {@code meterId} closest to {@code targetDate}
     * within {@code maxDayTolerance} days.
     */
    @Transactional(readOnly = true)
    public Optional<ArchivedMeterPhoto> findForMeterNearDate(
            UUID meterId, LocalDate targetDate, int maxDayTolerance) {
        if (meterId == null || targetDate == null) {
            return Optional.empty();
        }
        return repository.findArchivedByMeterOrderByDateDesc(meterId).stream()
                .filter(p -> p.readingDate() != null
                        && Math.abs(ChronoUnit.DAYS.between(p.readingDate(), targetDate)) <= maxDayTolerance)
                .min(Comparator.comparingLong(p -> Math.abs(ChronoUnit.DAYS.between(p.readingDate(), targetDate))));
    }

    @Transactional
    public void deleteByReadingId(UUID readingId) {
        if (readingId != null) {
            repository.deleteByMeterReadingId(readingId);
        }
    }

    static CompressedImage compressImage(byte[] rawBytes, String originalMimeType) {
        try {
            int orientation = readExifOrientation(rawBytes);
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(rawBytes));
            if (source == null) {
                if (rawBytes.length <= MAX_RAW_FALLBACK_BYTES) {
                    String mime = (originalMimeType != null && !originalMimeType.isBlank())
                            ? originalMimeType
                            : "application/octet-stream";
                    return new CompressedImage(rawBytes, mime);
                }
                throw new IllegalArgumentException("Unsupported image format exceeding size limit");
            }

            BufferedImage oriented = applyOrientation(source, orientation);
            BufferedImage scaled = scaleDownIfNeeded(oriented, MAX_DIMENSION_PX);
            BufferedImage rgb = ensureRgb(scaled);
            byte[] jpeg = encodeJpeg(rgb, JPEG_QUALITY);
            return new CompressedImage(jpeg, "image/jpeg");
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to process meter photo", exception);
        }
    }

    private static int readExifOrientation(byte[] rawBytes) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(rawBytes));
            ExifIFD0Directory dir = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (dir != null && dir.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return dir.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }
        } catch (Exception ignored) {
            // Non-EXIF images keep default orientation.
        }
        return 1;
    }

    private static BufferedImage applyOrientation(BufferedImage img, int orientation) {
        int w = img.getWidth();
        int h = img.getHeight();
        AffineTransform tx = new AffineTransform();
        int targetW = w;
        int targetH = h;
        switch (orientation) {
            case 3 -> tx.translate(w, h);
            case 6 -> {
                targetW = h;
                targetH = w;
                tx.translate(h, 0);
            }
            case 8 -> {
                targetW = h;
                targetH = w;
                tx.translate(0, w);
            }
            default -> {
                return img;
            }
        }
        switch (orientation) {
            case 3 -> tx.rotate(Math.PI);
            case 6 -> tx.rotate(Math.PI / 2.0);
            case 8 -> tx.rotate(-Math.PI / 2.0);
            default -> { }
        }
        BufferedImage rotated = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rotated.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, targetW, targetH);
        g.drawImage(img, tx, null);
        g.dispose();
        return rotated;
    }

    private static BufferedImage scaleDownIfNeeded(BufferedImage img, int maxDim) {
        int w = img.getWidth();
        int h = img.getHeight();
        int max = Math.max(w, h);
        if (max <= maxDim) {
            return img;
        }
        double scale = (double) maxDim / max;
        int targetW = Math.max(1, (int) Math.round(w * scale));
        int targetH = Math.max(1, (int) Math.round(h * scale));
        BufferedImage resized = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, targetW, targetH);
        g.drawImage(img, 0, 0, targetW, targetH, null);
        g.dispose();
        return resized;
    }

    private static BufferedImage ensureRgb(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_INT_RGB) {
            return img;
        }
        BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return rgb;
    }

    private static byte[] encodeJpeg(BufferedImage rgb, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            ByteArrayOutputStream fallback = new ByteArrayOutputStream();
            ImageIO.write(rgb, "jpg", fallback);
            return fallback.toByteArray();
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static String normalizeFileName(String fileName, String mimeType) {
        String base = (fileName == null || fileName.isBlank()) ? "meter-photo.jpg" : fileName.trim();
        if ("image/jpeg".equals(mimeType) && !base.toLowerCase().endsWith(".jpg")
                && !base.toLowerCase().endsWith(".jpeg")) {
            int dot = base.lastIndexOf('.');
            base = (dot > 0 ? base.substring(0, dot) : base) + ".jpg";
        }
        return base.length() > FILE_NAME_MAX_LENGTH ? base.substring(0, FILE_NAME_MAX_LENGTH) : base;
    }

    record CompressedImage(byte[] bytes, String mimeType) {
    }
}
