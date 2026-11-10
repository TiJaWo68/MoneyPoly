package de.in.mp.energy;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link MeterReading}.
 */
public interface MeterReadingRepository extends JpaRepository<MeterReading, UUID> {

    List<MeterReading> findByMeterIdOrderByReadingDateAsc(UUID meterId);

    List<MeterReading> findAllByOrderByReadingDateAsc();

    List<MeterReading> findByReadingDate(LocalDate readingDate);

    Optional<MeterReading> findByMeterIdAndReadingDate(UUID meterId, LocalDate readingDate);
}

