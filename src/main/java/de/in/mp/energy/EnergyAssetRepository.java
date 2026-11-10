package de.in.mp.energy;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link EnergyAsset}.
 */
public interface EnergyAssetRepository extends JpaRepository<EnergyAsset, UUID> {

    List<EnergyAsset> findAllByOrderByInstallationDateAscNameAsc();
}

