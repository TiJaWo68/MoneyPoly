package de.in.mp.energy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link EnergyHouse}.
 */
public interface EnergyHouseRepository extends JpaRepository<EnergyHouse, UUID> {

    List<EnergyHouse> findAllByOrderByDisplayOrderAscNameAsc();

    List<EnergyHouse> findByActiveTrueOrderByDisplayOrderAscNameAsc();

    boolean existsByNameIgnoreCase(String name);

    Optional<EnergyHouse> findByNameIgnoreCase(String name);
}
