package de.in.mp.energy;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link AppUserEnergyHouse}.
 */
public interface AppUserEnergyHouseRepository extends JpaRepository<AppUserEnergyHouse, AppUserEnergyHouseId> {

    List<AppUserEnergyHouse> findByAppUserId(UUID appUserId);

    void deleteByAppUserId(UUID appUserId);

    void deleteByHouseId(UUID houseId);
}
