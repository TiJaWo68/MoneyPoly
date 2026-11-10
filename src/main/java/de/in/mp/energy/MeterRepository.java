package de.in.mp.energy;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link Meter}.
 */
public interface MeterRepository extends JpaRepository<Meter, UUID> {

    List<Meter> findAllByOrderByDisplayOrderAscNameAsc();

    List<Meter> findByActiveTrueOrderByDisplayOrderAscNameAsc();

    List<Meter> findByAssetIdOrderByDisplayOrderAscNameAsc(UUID assetId);
}

