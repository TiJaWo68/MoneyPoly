package de.in.mp.energy;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite primary key for {@link AppUserEnergyHouse}.
 */
public class AppUserEnergyHouseId implements Serializable {

    private UUID appUserId;
    private UUID houseId;

    public AppUserEnergyHouseId() {
    }

    public AppUserEnergyHouseId(UUID appUserId, UUID houseId) {
        this.appUserId = appUserId;
        this.houseId = houseId;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AppUserEnergyHouseId that)) {
            return false;
        }
        return Objects.equals(appUserId, that.appUserId) && Objects.equals(houseId, that.houseId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(appUserId, houseId);
    }
}
