package de.in.mp.finance;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link WealthEvent} chart annotations.
 */
public interface WealthEventRepository extends JpaRepository<WealthEvent, UUID> {

    List<WealthEvent> findByHouseholdIdOrderByEventDateAscCreatedAtAsc(UUID householdId);

    List<WealthEvent> findAllByOrderByEventDateAscCreatedAtAsc();
}
