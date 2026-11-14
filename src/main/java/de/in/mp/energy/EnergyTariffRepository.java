package de.in.mp.energy;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link EnergyTariff}.
 */
public interface EnergyTariffRepository extends JpaRepository<EnergyTariff, UUID> {

    List<EnergyTariff> findAllByOrderByForMeterTypeAscValidFromDesc();

    List<EnergyTariff> findByHouseIdOrderByForMeterTypeAscValidFromDesc(UUID houseId);

    List<EnergyTariff> findByForMeterTypeOrderByValidFromAsc(MeterType forMeterType);

    List<EnergyTariff> findByHouseIdAndForMeterTypeOrderByValidFromAsc(UUID houseId, MeterType forMeterType);

    boolean existsByHouseId(UUID houseId);
}

