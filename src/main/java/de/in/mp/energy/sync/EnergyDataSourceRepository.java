package de.in.mp.energy.sync;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link EnergyDataSource}.
 */
public interface EnergyDataSourceRepository extends JpaRepository<EnergyDataSource, UUID> {

    List<EnergyDataSource> findByHouseIdOrderByNameAsc(UUID houseId);

    List<EnergyDataSource> findByHouseIdAndEnabledTrue(UUID houseId);

    List<EnergyDataSource> findByEnabledTrueAndAutoSyncEnabledTrue();
}
