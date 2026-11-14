package de.in.mp.energy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.in.mp.security.AppUser;
import de.in.mp.security.AppUserRepository;

/**
 * Domain service for {@link EnergyHouse} management and user-scoped house access resolution.
 */
@Service
public class EnergyHouseService {

    private static final int NAME_MAX_LENGTH = 80;
    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final EnergyHouseRepository houseRepository;
    private final AppUserEnergyHouseRepository assignmentRepository;
    private final AppUserRepository userRepository;
    private final EnergyAssetRepository assetRepository;
    private final MeterRepository meterRepository;
    private final EnergyTariffRepository tariffRepository;

    public EnergyHouseService(
            EnergyHouseRepository houseRepository,
            AppUserEnergyHouseRepository assignmentRepository,
            AppUserRepository userRepository,
            EnergyAssetRepository assetRepository,
            MeterRepository meterRepository,
            EnergyTariffRepository tariffRepository) {
        this.houseRepository = houseRepository;
        this.assignmentRepository = assignmentRepository;
        this.userRepository = userRepository;
        this.assetRepository = assetRepository;
        this.meterRepository = meterRepository;
        this.tariffRepository = tariffRepository;
    }

    @Transactional(readOnly = true)
    public List<EnergyHouse> findAll() {
        return houseRepository.findAllByOrderByDisplayOrderAscNameAsc();
    }

    @Transactional(readOnly = true)
    public List<EnergyHouse> findActive() {
        return houseRepository.findByActiveTrueOrderByDisplayOrderAscNameAsc();
    }

    @Transactional(readOnly = true)
    public EnergyHouse requireHouse(UUID id) {
        return houseRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Energy house not found: " + id));
    }

    @Transactional
    public EnergyHouse save(EnergyHouse house) {
        validate(house);
        return houseRepository.save(house);
    }

    @Transactional
    public void delete(UUID id) {
        EnergyHouse house = requireHouse(id);
        if (assetRepository.existsByHouseId(id)
                || meterRepository.existsByHouseId(id)
                || tariffRepository.existsByHouseId(id)) {
            throw new IllegalStateException(
                    "Cannot delete energy house '" + house.getName() + "' while assets, meters, or tariffs exist.");
        }
        assignmentRepository.deleteByHouseId(id);
        houseRepository.delete(house);
    }

    @Transactional(readOnly = true)
    public List<EnergyHouse> findAccessibleHousesForCurrentUser() {
        List<EnergyHouse> activeHouses = houseRepository.findByActiveTrueOrderByDisplayOrderAscNameAsc();
        if (isCurrentUserAdmin()) {
            return activeHouses;
        }
        Optional<AppUser> currentUser = resolveCurrentUser();
        if (currentUser.isEmpty()) {
            return List.of();
        }
        Map<UUID, HouseAccessRole> assignments = findAssignmentsMapForUser(currentUser.get().getId());
        return activeHouses.stream()
                .filter(house -> assignments.containsKey(house.getId()))
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean hasAccessibleHouseForCurrentUser() {
        return isCurrentUserAdmin() || !findAccessibleHousesForCurrentUser().isEmpty();
    }

    @Transactional(readOnly = true)
    public boolean canEditHouseForCurrentUser(UUID houseId) {
        if (houseId == null) {
            return false;
        }
        if (isCurrentUserAdmin()) {
            return true;
        }
        return resolveCurrentUser()
                .map(user -> findAssignmentsMapForUser(user.getId()).get(houseId) == HouseAccessRole.EDITOR)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public Map<UUID, HouseAccessRole> findAssignmentsMapForUser(UUID appUserId) {
        if (appUserId == null) {
            return Map.of();
        }
        return assignmentRepository.findByAppUserId(appUserId).stream()
                .collect(Collectors.toMap(
                        AppUserEnergyHouse::getHouseId,
                        AppUserEnergyHouse::getAccessRole,
                        (first, second) -> second));
    }

    @Transactional
    public void replaceAssignmentsForUser(UUID appUserId, Map<UUID, HouseAccessRole> assignments) {
        if (appUserId == null) {
            throw new IllegalArgumentException("User id must not be null");
        }
        assignmentRepository.deleteByAppUserId(appUserId);
        if (assignments == null || assignments.isEmpty()) {
            return;
        }
        List<AppUserEnergyHouse> entities = assignments.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                .map(entry -> new AppUserEnergyHouse(appUserId, entry.getKey(), entry.getValue()))
                .toList();
        assignmentRepository.saveAll(entities);
    }

    private Optional<AppUser> resolveCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal() == null) {
            return Optional.empty();
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof UserDetails userDetails) {
            return userRepository.findByUsername(userDetails.getUsername());
        }
        if (principal instanceof OAuth2User oAuth2User) {
            Object email = oAuth2User.getAttribute("email");
            if (email != null) {
                return userRepository.findByEmail(email.toString());
            }
        }
        return userRepository.findByUsername(authentication.getName());
    }

    private static boolean isCurrentUserAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> ROLE_ADMIN.equals(authority.getAuthority()));
    }

    private static void validate(EnergyHouse house) {
        if (house == null) {
            throw new IllegalArgumentException("Energy house must not be null");
        }
        if (house.getName() == null || house.getName().isBlank()) {
            throw new IllegalArgumentException("House name is required");
        }
        String trimmed = house.getName().trim();
        if (trimmed.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("House name must not exceed " + NAME_MAX_LENGTH + " characters");
        }
        house.setName(trimmed);
    }
}
