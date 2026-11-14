package de.in.mp.finance;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HouseholdRepository extends JpaRepository<Household, UUID> {

    List<Household> findByActiveTrueOrderByDemoAscNameAsc();

    List<Household> findAllByOrderByDemoAscNameAsc();

    Optional<Household> findByNameIgnoreCase(String name);

    Optional<Household> findFirstByDemoTrueOrderByCreatedAtAsc();

    Optional<Household> findFirstByDemoFalseOrderByCreatedAtAsc();
}
