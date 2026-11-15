package de.in.mp.finance;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.in.mp.loans.LoanContractRepository;
import de.in.mp.security.AppRole;
import de.in.mp.security.AppUser;
import de.in.mp.security.AppUserRepository;

/**
 * Domain service for {@link Household} management, tenant scoping and role/module
 * visibility resolution for the active user.
 */
@Service
public class HouseholdService {

    private static final int NAME_MAX_LENGTH = 100;

    private final HouseholdRepository householdRepository;
    private final AppUserRepository userRepository;
    private final AccountRepository accountRepository;
    private final LoanContractRepository loanContractRepository;

    public HouseholdService(
            HouseholdRepository householdRepository,
            AppUserRepository userRepository,
            AccountRepository accountRepository,
            LoanContractRepository loanContractRepository) {
        this.householdRepository = householdRepository;
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.loanContractRepository = loanContractRepository;
    }

    @Transactional(readOnly = true)
    public List<Household> findAll() {
        return householdRepository.findAllByOrderByDemoAscNameAsc();
    }

    @Transactional(readOnly = true)
    public List<Household> findActive() {
        return householdRepository.findByActiveTrueOrderByDemoAscNameAsc();
    }

    @Transactional(readOnly = true)
    public Optional<Household> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return householdRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public Household requireHousehold(UUID id) {
        return householdRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Household not found: " + id));
    }

    @Transactional(readOnly = true)
    public Optional<Household> findDefaultNonDemoHousehold() {
        return householdRepository.findFirstByDemoFalseOrderByCreatedAtAsc();
    }

    @Transactional(readOnly = true)
    public Optional<Household> findDemoHousehold() {
        return householdRepository.findFirstByDemoTrueOrderByCreatedAtAsc();
    }

    @Transactional
    public Household save(Household household) {
        if (household == null) {
            throw new IllegalArgumentException("Household must not be null");
        }
        String trimmedName = household.getName() == null ? "" : household.getName().trim();
        if (trimmedName.isBlank()) {
            throw new IllegalArgumentException("Household name must not be blank");
        }
        if (trimmedName.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("Household name exceeds max length of " + NAME_MAX_LENGTH);
        }
        householdRepository.findByNameIgnoreCase(trimmedName)
                .filter(existing -> !existing.getId().equals(household.getId()))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("Household name already exists: " + trimmedName);
                });
        household.setName(trimmedName);
        if (household.getDashboardWarningThreshold() == null) {
            household.setDashboardWarningThreshold(Household.DEFAULT_WARNING_THRESHOLD);
        }
        return householdRepository.save(household);
    }

    @Transactional(readOnly = true)
    public Optional<AppUser> getCurrentUser() {
        return resolveCurrentUser(SecurityContextHolder.getContext().getAuthentication());
    }

    @Transactional(readOnly = true)
    public Optional<UUID> getCurrentHouseholdId() {
        return getCurrentUser().map(AppUser::getHouseholdId);
    }

    @Transactional(readOnly = true)
    public Optional<UUID> getCurrentPersonId() {
        return getCurrentUser().map(AppUser::getPersonId);
    }

    @Transactional(readOnly = true)
    public UUID resolveEffectiveHouseholdId(UUID requestedHouseholdId) {
        if (requestedHouseholdId != null && isAdmin()) {
            return requestedHouseholdId;
        }
        return getCurrentHouseholdId()
                .orElseGet(() -> isAdmin()
                        ? findDefaultNonDemoHousehold().map(Household::getId).orElse(null)
                        : null);
    }

    @Transactional(readOnly = true)
    public List<Household> getAccessibleHouseholdsForCurrentUser() {
        if (isAdmin()) {
            return findActive();
        }
        return getCurrentHouseholdId()
                .flatMap(householdRepository::findById)
                .filter(Household::isActive)
                .map(List::of)
                .orElseGet(List::of);
    }

    @Transactional(readOnly = true)
    public boolean isAdmin() {
        return hasAuthority(AppRole.ROLE_ADMIN.name());
    }

    @Transactional(readOnly = true)
    public boolean isDemoUser() {
        return hasAuthority(AppRole.ROLE_DEMO.name()) && !isAdmin();
    }

    @Transactional(readOnly = true)
    public boolean canModifyCurrentHousehold() {
        return !isDemoUser();
    }

    @Transactional(readOnly = true)
    public boolean hasFinanceAccessForCurrentUser() {
        if (isAdmin()) {
            return true;
        }
        Optional<AppUser> user = getCurrentUser();
        if (user.isPresent()) {
            return user.get().getHouseholdId() != null;
        }
        return hasAuthority(AppRole.ROLE_USER.name()) || hasAuthority(AppRole.ROLE_DEMO.name());
    }

    @Transactional(readOnly = true)
    public boolean hasFinanceAccountsForCurrentUser() {
        if (!hasFinanceAccessForCurrentUser()) {
            return false;
        }
        if (isAdmin()) {
            return true;
        }
        return getCurrentHouseholdId()
                .map(id -> !accountRepository.findByHouseholdIdAndActiveTrueOrderByDisplayOrderAscNameAsc(id).isEmpty())
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean hasLenderLoanAccessForCurrentUser() {
        return hasFinanceAccessForCurrentUser();
    }

    @Transactional(readOnly = true)
    public boolean hasBorrowerLoanAccessForCurrentUser() {
        Optional<UUID> personId = getCurrentPersonId();
        if (personId.isEmpty()) {
            return false;
        }
        return hasAuthority(AppRole.ROLE_BORROWER.name())
                || loanContractRepository.existsByDebtorIdsContaining(personId.get());
    }

    @Transactional(readOnly = true)
    public boolean hasAnyLoanAccessForCurrentUser() {
        return hasLenderLoanAccessForCurrentUser() || hasBorrowerLoanAccessForCurrentUser();
    }

    @Transactional(readOnly = true)
    public BigDecimal resolveWarningThreshold(UUID householdId) {
        UUID effectiveId = resolveEffectiveHouseholdId(householdId);
        if (effectiveId == null) {
            return Household.DEFAULT_WARNING_THRESHOLD;
        }
        return householdRepository.findById(effectiveId)
                .map(Household::getDashboardWarningThreshold)
                .orElse(Household.DEFAULT_WARNING_THRESHOLD);
    }

    @Transactional(readOnly = true)
    public boolean isExitPlanEnabled(UUID householdId) {
        UUID effectiveId = resolveEffectiveHouseholdId(householdId);
        if (effectiveId == null) {
            return false;
        }
        return householdRepository.findById(effectiveId)
                .map(Household::isExitPlanEnabled)
                .orElse(false);
    }

    @Transactional
    public Household setExitPlanEnabled(UUID householdId, boolean enabled) {
        if (!canModifyCurrentHousehold()) {
            throw new IllegalStateException("Read-only demo user cannot modify household settings");
        }
        UUID effectiveId = resolveEffectiveHouseholdId(householdId);
        if (effectiveId == null) {
            throw new IllegalArgumentException("No active household available");
        }
        Household household = requireHousehold(effectiveId);
        household.setExitPlanEnabled(enabled);
        return householdRepository.save(household);
    }

    private boolean hasAuthority(String roleName) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(a -> roleName.equals(a.getAuthority()));
    }

    private Optional<AppUser> resolveCurrentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof UserDetails userDetails) {
            return userRepository.findByUsername(userDetails.getUsername());
        }
        if (principal instanceof OAuth2User oauth2User) {
            String sub = oauth2User.getAttribute("sub");
            if (sub != null) {
                Optional<AppUser> byProvider = userRepository.findByProviderId(sub);
                if (byProvider.isPresent()) {
                    return byProvider;
                }
            }
            String email = oauth2User.getAttribute("email");
            if (email != null) {
                return userRepository.findByEmail(email);
            }
        }
        String name = authentication.getName();
        if (name == null || "anonymousUser".equals(name)) {
            return Optional.empty();
        }
        return userRepository.findByUsername(name);
    }
}
