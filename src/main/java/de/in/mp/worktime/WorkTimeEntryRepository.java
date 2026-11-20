package de.in.mp.worktime;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for {@link WorkTimeEntry}.
 */
public interface WorkTimeEntryRepository extends JpaRepository<WorkTimeEntry, UUID> {

    List<WorkTimeEntry> findByProfileIdAndWorkDateBetweenOrderByWorkDateDescStartTimeAsc(
            UUID profileId, LocalDate from, LocalDate to);

    List<WorkTimeEntry> findByProfileIdAndWorkDateOrderByStartTimeAsc(UUID profileId, LocalDate workDate);

    List<WorkTimeEntry> findByProfileIdOrderByWorkDateDescStartTimeAsc(UUID profileId);

    Optional<WorkTimeEntry> findFirstByProfileIdAndWorkDateOrderByEndTimeDesc(UUID profileId, LocalDate workDate);
}
