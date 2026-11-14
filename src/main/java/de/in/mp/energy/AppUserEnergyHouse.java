package de.in.mp.energy;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * Maps an application user to an {@link EnergyHouse} with a specific {@link HouseAccessRole}.
 */
@Entity
@Table(name = "app_user_energy_house")
@IdClass(AppUserEnergyHouseId.class)
public class AppUserEnergyHouse {

    @Id
    @Column(name = "app_user_id", nullable = false)
    private UUID appUserId;

    @Id
    @Column(name = "house_id", nullable = false)
    private UUID houseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_role", nullable = false, length = 20)
    private HouseAccessRole accessRole = HouseAccessRole.EDITOR;

    public AppUserEnergyHouse() {
    }

    public AppUserEnergyHouse(UUID appUserId, UUID houseId, HouseAccessRole accessRole) {
        this.appUserId = appUserId;
        this.houseId = houseId;
        this.accessRole = accessRole == null ? HouseAccessRole.EDITOR : accessRole;
    }

    public UUID getAppUserId() {
        return appUserId;
    }

    public void setAppUserId(UUID appUserId) {
        this.appUserId = appUserId;
    }

    public UUID getHouseId() {
        return houseId;
    }

    public void setHouseId(UUID houseId) {
        this.houseId = houseId;
    }

    public HouseAccessRole getAccessRole() {
        return accessRole;
    }

    public void setAccessRole(HouseAccessRole accessRole) {
        this.accessRole = accessRole == null ? HouseAccessRole.EDITOR : accessRole;
    }
}
