package de.in.mp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import de.in.mp.loans.LoanContractRepository;
import de.in.mp.security.AppRole;
import de.in.mp.security.AppUser;
import de.in.mp.security.AppUserRepository;

class HouseholdServiceTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void resolvesHouseholdAndModuleAccessForRegularUser() {
        HouseholdRepository householdRepo = mock(HouseholdRepository.class);
        AppUserRepository userRepo = mock(AppUserRepository.class);
        AccountRepository accountRepo = mock(AccountRepository.class);
        LoanContractRepository loanRepo = mock(LoanContractRepository.class);

        UUID householdId = UUID.randomUUID();
        AppUser user = new AppUser();
        user.setUsername("anna");
        user.setHouseholdId(householdId);
        user.setRoles(Set.of(AppRole.ROLE_USER));

        Household household = new Household();
        household.setId(householdId);
        household.setName("Familie A");
        household.setActive(true);

        when(userRepo.findByUsername("anna")).thenReturn(Optional.of(user));
        when(householdRepo.findById(householdId)).thenReturn(Optional.of(household));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "anna",
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        HouseholdService service = new HouseholdService(householdRepo, userRepo, accountRepo, loanRepo);

        assertThat(service.getCurrentHouseholdId()).contains(householdId);
        assertThat(service.hasFinanceAccessForCurrentUser()).isTrue();
        assertThat(service.hasLenderLoanAccessForCurrentUser()).isTrue();
        assertThat(service.canModifyCurrentHousehold()).isTrue();
        assertThat(service.getAccessibleHouseholdsForCurrentUser()).containsExactly(household);
    }

    @Test
    void hidesFinanceAccessForBorrowerOnlyUser() {
        HouseholdRepository householdRepo = mock(HouseholdRepository.class);
        AppUserRepository userRepo = mock(AppUserRepository.class);
        AccountRepository accountRepo = mock(AccountRepository.class);
        LoanContractRepository loanRepo = mock(LoanContractRepository.class);

        UUID personId = UUID.randomUUID();
        AppUser borrower = new AppUser();
        borrower.setUsername("bob");
        borrower.setPersonId(personId);
        borrower.setHouseholdId(null);
        borrower.setRoles(Set.of(AppRole.ROLE_BORROWER));

        when(userRepo.findByUsername("bob")).thenReturn(Optional.of(borrower));
        when(loanRepo.existsByDebtorIdsContaining(personId)).thenReturn(true);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "bob",
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_BORROWER"))));

        HouseholdService service = new HouseholdService(householdRepo, userRepo, accountRepo, loanRepo);

        assertThat(service.hasFinanceAccessForCurrentUser()).isFalse();
        assertThat(service.hasLenderLoanAccessForCurrentUser()).isFalse();
        assertThat(service.hasBorrowerLoanAccessForCurrentUser()).isTrue();
        assertThat(service.hasAnyLoanAccessForCurrentUser()).isTrue();
    }

    @Test
    void demoUserIsReadOnly() {
        HouseholdRepository householdRepo = mock(HouseholdRepository.class);
        AppUserRepository userRepo = mock(AppUserRepository.class);
        AccountRepository accountRepo = mock(AccountRepository.class);
        LoanContractRepository loanRepo = mock(LoanContractRepository.class);

        UUID demoHouseholdId = UUID.randomUUID();
        AppUser demo = new AppUser();
        demo.setUsername("demo");
        demo.setHouseholdId(demoHouseholdId);
        demo.setRoles(Set.of(AppRole.ROLE_DEMO));

        when(userRepo.findByUsername("demo")).thenReturn(Optional.of(demo));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "demo",
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_DEMO"))));

        HouseholdService service = new HouseholdService(householdRepo, userRepo, accountRepo, loanRepo);

        assertThat(service.isDemoUser()).isTrue();
        assertThat(service.canModifyCurrentHousehold()).isFalse();
        assertThat(service.hasFinanceAccessForCurrentUser()).isTrue();
    }

    @Test
    void adminSeesAllActiveHouseholdsAndCanModify() {
        HouseholdRepository householdRepo = mock(HouseholdRepository.class);
        AppUserRepository userRepo = mock(AppUserRepository.class);
        AccountRepository accountRepo = mock(AccountRepository.class);
        LoanContractRepository loanRepo = mock(LoanContractRepository.class);

        Household h1 = new Household();
        h1.setId(UUID.randomUUID());
        h1.setName("Familie");
        h1.setActive(true);

        Household hDemo = new Household();
        hDemo.setId(UUID.randomUUID());
        hDemo.setName("Demo");
        hDemo.setDemo(true);
        hDemo.setActive(true);

        when(householdRepo.findByActiveTrueOrderByDemoAscNameAsc()).thenReturn(List.of(h1, hDemo));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "admin",
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        HouseholdService service = new HouseholdService(householdRepo, userRepo, accountRepo, loanRepo);

        assertThat(service.isAdmin()).isTrue();
        assertThat(service.canModifyCurrentHousehold()).isTrue();
        assertThat(service.getAccessibleHouseholdsForCurrentUser()).containsExactly(h1, hDemo);
    }

    @Test
    void togglesExitPlanOptInForModifiableHousehold() {
        HouseholdRepository householdRepo = mock(HouseholdRepository.class);
        AppUserRepository userRepo = mock(AppUserRepository.class);
        AccountRepository accountRepo = mock(AccountRepository.class);
        LoanContractRepository loanRepo = mock(LoanContractRepository.class);

        UUID householdId = UUID.randomUUID();
        Household household = new Household();
        household.setId(householdId);
        household.setName("Familie");
        household.setExitPlanEnabled(false);

        when(householdRepo.findById(householdId)).thenReturn(Optional.of(household));
        when(householdRepo.save(org.mockito.ArgumentMatchers.any(Household.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        HouseholdService service = new HouseholdService(householdRepo, userRepo, accountRepo, loanRepo);

        assertThat(service.isExitPlanEnabled(householdId)).isFalse();

        Household updated = service.setExitPlanEnabled(householdId, true);
        assertThat(updated.isExitPlanEnabled()).isTrue();
        assertThat(service.isExitPlanEnabled(householdId)).isTrue();
    }

    @Test
    void rejectsExitPlanToggleForReadOnlyDemoUser() {
        HouseholdRepository householdRepo = mock(HouseholdRepository.class);
        AppUserRepository userRepo = mock(AppUserRepository.class);
        AccountRepository accountRepo = mock(AccountRepository.class);
        LoanContractRepository loanRepo = mock(LoanContractRepository.class);

        UUID demoId = UUID.randomUUID();
        AppUser demo = new AppUser();
        demo.setUsername("demo");
        demo.setHouseholdId(demoId);
        demo.setRoles(Set.of(AppRole.ROLE_DEMO));
        when(userRepo.findByUsername("demo")).thenReturn(Optional.of(demo));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "demo", null, List.of(new SimpleGrantedAuthority("ROLE_DEMO"))));

        HouseholdService service = new HouseholdService(householdRepo, userRepo, accountRepo, loanRepo);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.setExitPlanEnabled(demoId, true))
                .isInstanceOf(IllegalStateException.class);
    }
}
