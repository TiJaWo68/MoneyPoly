package de.in.mp.energy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.in.mp.energy.MeterReadingPhotoService.CompressedImage;

class MeterReadingPhotoServiceTest {

    private MeterReadingPhotoRepository repository;
    private MeterReadingPhotoService service;

    @BeforeEach
    void setUp() {
        repository = mock(MeterReadingPhotoRepository.class);
        service = new MeterReadingPhotoService(repository);
    }

    @Test
    void resizesLargeImagesToMaxDimensionJpegAndUpsertsExistingPhoto() throws Exception {
        byte[] largePng = createTestPng(2400, 1600);
        UUID readingId = UUID.randomUUID();
        MeterReadingPhoto existing = new MeterReadingPhoto();
        existing.setId(UUID.randomUUID());
        existing.setMeterReadingId(readingId);

        when(repository.findByMeterReadingId(readingId)).thenReturn(Optional.of(existing));
        when(repository.save(any(MeterReadingPhoto.class))).thenAnswer(inv -> inv.getArgument(0));

        MeterReadingPhoto saved = service.savePhoto(readingId, "meter-reading.png", "image/png", largePng);

        assertThat(saved.getId()).isEqualTo(existing.getId());
        assertThat(saved.getMimeType()).isEqualTo("image/jpeg");
        assertThat(saved.getFileName()).isEqualTo("meter-reading.jpg");
        assertThat(saved.getImageData().length).isLessThan(largePng.length);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(saved.getImageData()));
        assertThat(decoded.getWidth()).isEqualTo(MeterReadingPhotoService.MAX_DIMENSION_PX);
        assertThat(decoded.getHeight()).isEqualTo(853);
    }

    @Test
    void compressesWithoutUpscalingSmallImages() throws Exception {
        byte[] smallPng = createTestPng(400, 300);

        CompressedImage compressed = MeterReadingPhotoService.compressImage(smallPng, "image/png");

        assertThat(compressed.mimeType()).isEqualTo("image/jpeg");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(compressed.bytes()));
        assertThat(decoded.getWidth()).isEqualTo(400);
        assertThat(decoded.getHeight()).isEqualTo(300);
    }

    @Test
    void resolvesClosestPhotoWithinToleranceAndLatestForMeter() {
        UUID meterId = UUID.randomUUID();
        LocalDate targetEnd = LocalDate.of(2026, 10, 31);

        ArchivedMeterPhoto novPhoto = archivedPhoto(LocalDate.of(2026, 11, 18), "25010");
        ArchivedMeterPhoto octPhoto = archivedPhoto(LocalDate.of(2026, 10, 30), "24915");
        when(repository.findArchivedByMeterOrderByDateDesc(meterId))
                .thenReturn(List.of(novPhoto, octPhoto));

        assertThat(service.findLatestForMeter(meterId)).contains(novPhoto);
        assertThat(service.findForMeterNearDate(meterId, targetEnd, 2)).contains(octPhoto);
        assertThat(service.findForMeterNearDate(meterId, targetEnd, 0)).isEmpty();
    }

    @Test
    void skipsRepositoryQueryWhenReadingIdsEmptyAndDelegatesDelete() {
        assertThat(service.findArchivedReadingIds(List.of())).isEmpty();

        UUID readingId = UUID.randomUUID();
        when(repository.findArchivedReadingIds(List.of(readingId))).thenReturn(Set.of(readingId));
        assertThat(service.findArchivedReadingIds(List.of(readingId))).containsExactly(readingId);

        service.deleteByReadingId(readingId);
        verify(repository).deleteByMeterReadingId(readingId);
    }

    private static ArchivedMeterPhoto archivedPhoto(LocalDate date, String value) {
        MeterReadingPhoto p = new MeterReadingPhoto();
        p.setId(UUID.randomUUID());
        p.setMeterReadingId(UUID.randomUUID());
        p.setMimeType("image/jpeg");
        p.setImageData(new byte[] { 1, 2, 3 });
        return new ArchivedMeterPhoto(p, date, new BigDecimal(value));
    }

    private static byte[] createTestPng(int width, int height) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.DARK_GRAY);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.WHITE);
        g.fillRect(width / 4, height / 4, width / 2, height / 2);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
