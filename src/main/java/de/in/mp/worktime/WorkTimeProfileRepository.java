package de.in.mp.worktime;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for {@link WorkTimeProfile}.
 */
public interface WorkTimeProfileRepository extends JpaRepository<WorkTimeProfile, UUID> {

    Optional<WorkTimeProfile> findByAppUserId(UUID appUserId);

    List<WorkTimeProfile> findByEnabledTrueOrderByCreatedAtAsc();

    boolean existsByEnabledTrue();

    boolean existsByAppUserIdAndEnabledTrue(UUID appUserId);
}
