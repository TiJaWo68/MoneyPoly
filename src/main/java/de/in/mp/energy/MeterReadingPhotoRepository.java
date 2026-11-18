package de.in.mp.energy;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for archived meter reading photos.
 */
public interface MeterReadingPhotoRepository extends JpaRepository<MeterReadingPhoto, UUID> {

    Optional<MeterReadingPhoto> findByMeterReadingId(UUID meterReadingId);

    void deleteByMeterReadingId(UUID meterReadingId);

    @Query("select p.meterReadingId from MeterReadingPhoto p where p.meterReadingId in :readingIds")
    Set<UUID> findArchivedReadingIds(@Param("readingIds") Collection<UUID> readingIds);

    @Query("""
            select new de.in.mp.energy.ArchivedMeterPhoto(p, r.readingDate, r.readingValue)
            from MeterReadingPhoto p, MeterReading r
            where p.meterReadingId = r.id and r.meterId = :meterId
            order by r.readingDate desc
            """)
    List<ArchivedMeterPhoto> findArchivedByMeterOrderByDateDesc(@Param("meterId") UUID meterId);
}
