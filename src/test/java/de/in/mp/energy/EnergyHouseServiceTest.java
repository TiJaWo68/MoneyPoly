package de.in.mp.energy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import de.in.mp.security.AppUser;
import de.in.mp.security.AppUserRepository;

class EnergyHouseServiceTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void adminSeesAndCanEditAllActiveHouses() {
        EnergyHouseRepository houseRepo = mock(EnergyHouseRepository.class);
        AppUserEnergyHouseRepository assignmentRepo = mock(AppUserEnergyHouseRepository.class);
        AppUserRepository userRepo = mock(AppUserRepository.class);
        EnergyAssetRepository assetRepo = mock(EnergyAssetRepository.class);
        MeterRepository meterRepo = mock(MeterRepository.class);
        EnergyTariffRepository tariffRepo = mock(EnergyTariffRepository.class);

        EnergyHouse house1 = house(UUID.randomUUID(), "Haus 1", 1, true);
        EnergyHouse house2 = house(UUID.randomUUID(), "Haus 2", 2, true);
        when(houseRepo.findByActiveTrueOrderByDisplayOrderAscNameAsc()).thenReturn(List.of(house1, house2));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "admin", "pw", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        EnergyHouseService service = new EnergyHouseService(
                houseRepo, assignmentRepo, userRepo, assetRepo, meterRepo, tariffRepo);

        assertThat(service.findAccessibleHousesForCurrentUser()).containsExactly(house1, house2);
        assertThat(service.canEditHouseForCurrentUser(house1.getId())).isTrue();
        assertThat(service.canEditHouseForCurrentUser(house2.getId())).isTrue();
    }

    @Test
    void regularUserSeesOnlyAssignedActiveHousesAndRespectsViewerRole() {
        EnergyHouseRepository houseRepo = mock(EnergyHouseRepository.class);
        AppUserEnergyHouseRepository assignmentRepo = mock(AppUserEnergyHouseRepository.class);
        AppUserRepository userRepo = mock(AppUserRepository.class);
        EnergyAssetRepository assetRepo = mock(EnergyAssetRepository.class);
        MeterRepository meterRepo = mock(MeterRepository.class);
        EnergyTariffRepository tariffRepo = mock(EnergyTariffRepository.class);

        UUID userId = UUID.randomUUID();
        AppUser user = new AppUser();
        user.setId(userId);
        user.setUsername("family-a");

        EnergyHouse house1 = house(UUID.randomUUID(), "Haus 1", 1, true);
        EnergyHouse house2 = house(UUID.randomUUID(), "Haus 2", 2, true);
        EnergyHouse inactiveHouse = house(UUID.randomUUID(), "Alt", 3, false);

        when(userRepo.findByUsername("family-a")).thenReturn(Optional.of(user));
        when(assignmentRepo.findByAppUserId(userId)).thenReturn(List.of(
                new AppUserEnergyHouse(userId, house1.getId(), HouseAccessRole.VIEWER),
                new AppUserEnergyHouse(userId, inactiveHouse.getId(), HouseAccessRole.EDITOR)));
        when(houseRepo.findByActiveTrueOrderByDisplayOrderAscNameAsc()).thenReturn(List.of(house1, house2));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "family-a", "pw", List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        EnergyHouseService service = new EnergyHouseService(
                houseRepo, assignmentRepo, userRepo, assetRepo, meterRepo, tariffRepo);

        assertThat(service.findAccessibleHousesForCurrentUser()).containsExactly(house1);
        assertThat(service.canEditHouseForCurrentUser(house1.getId())).isFalse();
        assertThat(service.canEditHouseForCurrentUser(house2.getId())).isFalse();
    }

    @Test
    void rejectsDeletingHouseWhenAssetsMetersOrTariffsExist() {
        EnergyHouseRepository houseRepo = mock(EnergyHouseRepository.class);
        AppUserEnergyHouseRepository assignmentRepo = mock(AppUserEnergyHouseRepository.class);
        AppUserRepository userRepo = mock(AppUserRepository.class);
        EnergyAssetRepository assetRepo = mock(EnergyAssetRepository.class);
        MeterRepository meterRepo = mock(MeterRepository.class);
        EnergyTariffRepository tariffRepo = mock(EnergyTariffRepository.class);

        UUID houseId = UUID.randomUUID();
        EnergyHouse house1 = house(houseId, "Haus 1", 1, true);
        when(houseRepo.findById(houseId)).thenReturn(Optional.of(house1));
        when(meterRepo.existsByHouseId(houseId)).thenReturn(true);

        EnergyHouseService service = new EnergyHouseService(
                houseRepo, assignmentRepo, userRepo, assetRepo, meterRepo, tariffRepo);

        assertThatThrownBy(() -> service.delete(houseId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Haus 1");
    }

    @Test
    void replacesUserAssignmentsAtomically() {
        EnergyHouseRepository houseRepo = mock(EnergyHouseRepository.class);
        AppUserEnergyHouseRepository assignmentRepo = mock(AppUserEnergyHouseRepository.class);
        AppUserRepository userRepo = mock(AppUserRepository.class);
        EnergyAssetRepository assetRepo = mock(EnergyAssetRepository.class);
        MeterRepository meterRepo = mock(MeterRepository.class);
        EnergyTariffRepository tariffRepo = mock(EnergyTariffRepository.class);

        UUID userId = UUID.randomUUID();
        UUID houseId = UUID.randomUUID();
        EnergyHouseService service = new EnergyHouseService(
                houseRepo, assignmentRepo, userRepo, assetRepo, meterRepo, tariffRepo);

        service.replaceAssignmentsForUser(userId, Map.of(houseId, HouseAccessRole.EDITOR));

        verify(assignmentRepo).deleteByAppUserId(userId);
        verify(assignmentRepo).saveAll(any());
    }

    private static EnergyHouse house(UUID id, String name, int order, boolean active) {
        EnergyHouse house = new EnergyHouse();
        house.setId(id);
        house.setName(name);
        house.setDisplayOrder(order);
        house.setActive(active);
        return house;
    }
}
