package de.in.mp.worktime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import de.in.mp.finance.HouseholdService;
import de.in.mp.finance.Person;
import de.in.mp.finance.PersonRepository;
import de.in.mp.security.AppRole;
import de.in.mp.security.AppUser;
import de.in.mp.security.AppUserRepository;
import de.in.mp.worktime.WorkTimeSummary.DaySummary;
import de.in.mp.worktime.WorkTimeSummary.PeriodSummary;
import de.in.mp.worktime.WorkTimeSummary.ProfileOption;

class WorkTimeServiceTest {

    private WorkTimeProfileRepository profileRepository;
    private WorkTimeEntryRepository entryRepository;
    private AppUserRepository userRepository;
    private PersonRepository personRepository;
    private HouseholdService householdService;
    private WorkTimeService service;

    private AppUser teacherUser;
    private AppUser adminUser;
    private AppUser otherUser;
    private WorkTimeProfile teacherProfile;

    @BeforeEach
    void setUp() {
        profileRepository = mock(WorkTimeProfileRepository.class);
        entryRepository = mock(WorkTimeEntryRepository.class);
        userRepository = mock(AppUserRepository.class);
        personRepository = mock(PersonRepository.class);
        householdService = mock(HouseholdService.class);
        service = new WorkTimeService(
                profileRepository, entryRepository, userRepository, personRepository, householdService);

        teacherUser = user(UUID.randomUUID(), "anna", AppRole.ROLE_USER);
        adminUser = user(UUID.randomUUID(), "till", AppRole.ROLE_ADMIN);
        otherUser = user(UUID.randomUUID(), "max", AppRole.ROLE_USER);

        teacherProfile = new WorkTimeProfile();
        teacherProfile.setId(UUID.randomUUID());
        teacherProfile.setAppUserId(teacherUser.getId());
        teacherProfile.setEnabled(true);
        teacherProfile.setFullTimeWeeklyHours(new BigDecimal("40.00"));
        teacherProfile.setFullTimeTeachingUnits(new BigDecimal("27.00"));
        teacherProfile.setContractTeachingUnits(new BigDecimal("20.00"));
        teacherProfile.setWorkDaysPerWeek(5);
        teacherProfile.setDailyWarningHours(new BigDecimal("10.00"));
        teacherProfile.setMinRestHours(new BigDecimal("11.00"));

        when(householdService.isDemoUser()).thenReturn(false);
        when(userRepository.findByUsername("anna")).thenReturn(Optional.of(teacherUser));
        when(userRepository.findByUsername("till")).thenReturn(Optional.of(adminUser));
        when(userRepository.findByUsername("max")).thenReturn(Optional.of(otherUser));
        when(profileRepository.findById(teacherProfile.getId())).thenReturn(Optional.of(teacherProfile));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void calculatesTeacherProRataContractTarget() {
        assertThat(teacherProfile.getPaidWeeklyHours()).isEqualByComparingTo("29.63");
        // 20 / 27 * 40h * 60m = 1777.777... -> 1778 minutes (29h 38m), 356m/day (5h 56m)
        assertThat(teacherProfile.getPaidWeeklyMinutes()).isEqualTo(1778L);
        assertThat(teacherProfile.getPaidDailyMinutes()).isEqualTo(356L);
        assertThat(WorkTimeService.formatMinutes(1778L)).isEqualTo("29h 38m");
        assertThat(WorkTimeService.formatSignedMinutes(125L)).isEqualTo("+2h 05m");
    }

    @Test
    void summarizesMultipleBlocksOvertimeEveningWeekendAndRestViolations() {
        authenticate("anna", "ROLE_USER");

        LocalDate wed = LocalDate.of(2026, 11, 18);
        LocalDate thu = LocalDate.of(2026, 11, 19);
        LocalDate sat = LocalDate.of(2026, 11, 21);

        List<WorkTimeEntry> entries = List.of(
                entry(sat, LocalTime.of(10, 0), LocalTime.of(12, 0), WorkTimeCategory.HOME_OFFICE, true, "Klausuren"),
                entry(thu, LocalTime.of(7, 30), LocalTime.of(12, 0), WorkTimeCategory.SCHOOL, true, "Unterricht"),
                entry(wed, LocalTime.of(7, 45), LocalTime.of(13, 15), WorkTimeCategory.SCHOOL, true, "1.-6. Std"),
                entry(wed, LocalTime.of(13, 15), LocalTime.of(13, 40), WorkTimeCategory.COMMUTE, false, "Rückweg"),
                entry(wed, LocalTime.of(15, 0), LocalTime.of(17, 30), WorkTimeCategory.HOME_OFFICE, true, "Korrektur"),
                entry(wed, LocalTime.of(18, 30), LocalTime.of(20, 30), WorkTimeCategory.MEETING, true, "Elternabend"),
                entry(wed, LocalTime.of(20, 30), LocalTime.of(21, 0), WorkTimeCategory.HOME_OFFICE, true, "Mail"));

        when(entryRepository.findByProfileIdAndWorkDateBetweenOrderByWorkDateDescStartTimeAsc(
                teacherProfile.getId(), LocalDate.of(2026, 11, 15), LocalDate.of(2026, 11, 22)))
                .thenReturn(entries);

        PeriodSummary summary = service.summarizePeriod(
                teacherProfile,
                LocalDate.of(2026, 11, 16),
                LocalDate.of(2026, 11, 22),
                sat);

        // Wed: 330 + 150 + 120 + 30 = 630m (10h 30m), Thu: 270m, Sat: 120m -> total 1020m
        assertThat(summary.totalWorkMinutes()).isEqualTo(1020L);
        assertThat(summary.schoolMinutes()).isEqualTo(600L);
        assertThat(summary.homeOfficeMinutes()).isEqualTo(300L);
        assertThat(summary.meetingMinutes()).isEqualTo(120L);
        assertThat(summary.commuteMinutes()).isEqualTo(25L);
        assertThat(summary.commuteTrips()).isEqualTo(1L);
        // Wed evening >= 18:00: 120 + 30 = 150m
        assertThat(summary.eveningMinutes()).isEqualTo(150L);
        assertThat(summary.weekendMinutes()).isEqualTo(120L);
        assertThat(summary.daysOverDailyLimit()).isEqualTo(1);
        // Wed ends 21:00 -> Thu starts 07:30 = 630m (< 660m minimum rest)
        assertThat(summary.shortRestCount()).isEqualTo(1);

        DaySummary thuSummary = summary.days().stream()
                .filter(d -> d.date().equals(thu))
                .findFirst()
                .orElseThrow();
        assertThat(thuSummary.shortRestViolation()).isTrue();
        assertThat(thuSummary.restMinutesSincePreviousDay()).isEqualTo(630L);

        // Target Mon-Fri + today=Sat -> 5 scheduled workdays * 356m = 1780m; overtime = 1020 - 1780 = -760m
        assertThat(summary.targetMinutes()).isEqualTo(5 * 356L);
        assertThat(summary.overtimeMinutes()).isEqualTo(1020L - 5 * 356L);
    }

    @Test
    void rejectsOverlappingBlocksButAllowsAdjacentAndEditingSelf() {
        authenticate("anna", "ROLE_USER");
        LocalDate day = LocalDate.of(2026, 11, 18);
        UUID existingId = UUID.randomUUID();
        WorkTimeEntry existing = entry(day, LocalTime.of(8, 0), LocalTime.of(10, 0), WorkTimeCategory.SCHOOL, true, null);
        existing.setId(existingId);
        existing.setProfileId(teacherProfile.getId());

        when(entryRepository.findByProfileIdAndWorkDateOrderByStartTimeAsc(teacherProfile.getId(), day))
                .thenReturn(List.of(existing));
        when(entryRepository.findById(existingId)).thenReturn(Optional.of(existing));
        when(entryRepository.save(any(WorkTimeEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> service.saveEntry(
                teacherProfile.getId(), null, day, LocalTime.of(9, 0), LocalTime.of(11, 0),
                WorkTimeCategory.HOME_OFFICE, true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("08:00–10:00");

        WorkTimeEntry adjacent = service.saveEntry(
                teacherProfile.getId(), null, day, LocalTime.of(10, 0), LocalTime.of(12, 0),
                WorkTimeCategory.HOME_OFFICE, true, "Nachbereitung");
        assertThat(adjacent.getDurationMinutes()).isEqualTo(120L);

        WorkTimeEntry updatedSelf = service.saveEntry(
                teacherProfile.getId(), existingId, day, LocalTime.of(8, 0), LocalTime.of(10, 30),
                WorkTimeCategory.SCHOOL, true, "Verlängert");
        assertThat(updatedSelf.getDurationMinutes()).isEqualTo(150L);
    }

    @Test
    void enforcesStrictPersonalVisibilityAndAdminAccess() {
        // 1. User without enabled profile cannot see menu item
        authenticate("max", "ROLE_USER");
        when(profileRepository.existsByAppUserIdAndEnabledTrue(otherUser.getId())).thenReturn(false);
        when(profileRepository.findByAppUserId(otherUser.getId())).thenReturn(Optional.empty());
        assertThat(service.canCurrentUserAccessWorkTime()).isFalse();
        assertThat(service.getAccessibleProfilesForCurrentUser()).isEmpty();
        assertThatThrownBy(() -> service.findEntriesForDay(teacherProfile.getId(), LocalDate.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Access denied");

        // 2. Non-admin cannot configure profiles
        assertThatThrownBy(() -> service.saveProfileForUser(
                otherUser.getId(), true, new BigDecimal("40"), new BigDecimal("27"),
                new BigDecimal("20"), 5, new BigDecimal("10"), new BigDecimal("11")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Administrator");

        // 3. Enabled user sees only their own profile
        authenticate("anna", "ROLE_USER");
        when(profileRepository.existsByAppUserIdAndEnabledTrue(teacherUser.getId())).thenReturn(true);
        when(profileRepository.findByAppUserId(teacherUser.getId())).thenReturn(Optional.of(teacherProfile));
        when(userRepository.findAll()).thenReturn(List.of(teacherUser, adminUser, otherUser));
        Person person = new Person();
        person.setId(UUID.randomUUID());
        person.setName("Anna");
        teacherUser.setPersonId(person.getId());
        when(personRepository.findAll()).thenReturn(List.of(person));

        assertThat(service.canCurrentUserAccessWorkTime()).isTrue();
        List<ProfileOption> ownOptions = service.getAccessibleProfilesForCurrentUser();
        assertThat(ownOptions).hasSize(1);
        assertThat(ownOptions.get(0).displayName()).isEqualTo("Anna (anna)");

        // 4. Admin sees menu only when at least one profile is enabled, and manages all enabled profiles
        authenticate("till", "ROLE_ADMIN");
        when(profileRepository.existsByEnabledTrue()).thenReturn(false);
        assertThat(service.canCurrentUserAccessWorkTime()).isFalse();

        when(profileRepository.existsByEnabledTrue()).thenReturn(true);
        when(profileRepository.findByEnabledTrueOrderByCreatedAtAsc()).thenReturn(List.of(teacherProfile));
        assertThat(service.canCurrentUserAccessWorkTime()).isTrue();
        assertThat(service.getAccessibleProfilesForCurrentUser()).hasSize(1);

        // 5. Demo user is always blocked
        when(householdService.isDemoUser()).thenReturn(true);
        assertThat(service.canCurrentUserAccessWorkTime()).isFalse();
        assertThat(service.getAccessibleProfilesForCurrentUser()).isEmpty();
    }

    @Test
    void copiesLastWeekSchoolBlocksAndBuildsCsvExport() {
        authenticate("till", "ROLE_ADMIN");
        LocalDate lastWeek = LocalDate.of(2026, 11, 11);
        LocalDate target = LocalDate.of(2026, 11, 18);

        List<WorkTimeEntry> lastWeekBlocks = List.of(
                entry(lastWeek, LocalTime.of(7, 45), LocalTime.of(13, 15), WorkTimeCategory.SCHOOL, true, "Unterricht"),
                entry(lastWeek, LocalTime.of(15, 0), LocalTime.of(17, 0), WorkTimeCategory.HOME_OFFICE, true, "Heimarbeit"));
        List<WorkTimeEntry> targetExisting = new ArrayList<>();

        when(entryRepository.findByProfileIdAndWorkDateOrderByStartTimeAsc(teacherProfile.getId(), lastWeek))
                .thenReturn(lastWeekBlocks);
        when(entryRepository.findByProfileIdAndWorkDateOrderByStartTimeAsc(teacherProfile.getId(), target))
                .thenReturn(targetExisting);
        when(entryRepository.save(any(WorkTimeEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        int copied = service.copyDayBlocks(teacherProfile.getId(), lastWeek, target, true);
        assertThat(copied).isEqualTo(1);
        assertThat(targetExisting).hasSize(1);
        assertThat(targetExisting.get(0).getCategory()).isEqualTo(WorkTimeCategory.SCHOOL);

        when(entryRepository.findByProfileIdAndWorkDateBetweenOrderByWorkDateDescStartTimeAsc(
                teacherProfile.getId(), target.minusDays(1), target))
                .thenReturn(targetExisting);

        String csv = service.buildCsvExport(teacherProfile, target, target);
        assertThat(csv).startsWith("Datum;Start;Ende;Dauer_Min;Kategorie;Zaehlt_als_Arbeitszeit;Notiz\n");
        assertThat(csv).contains("2026-11-18;07:45;13:15;330;SCHOOL;ja;Unterricht");
    }

    private static AppUser user(UUID id, String username, AppRole role) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setUsername(username);
        user.setRoles(Set.of(role));
        user.setEnabled(true);
        return user;
    }

    private static WorkTimeEntry entry(
            LocalDate date,
            LocalTime start,
            LocalTime end,
            WorkTimeCategory category,
            boolean countsAsWork,
            String note) {
        WorkTimeEntry entry = new WorkTimeEntry();
        entry.setId(UUID.randomUUID());
        entry.setWorkDate(date);
        entry.setStartTime(start);
        entry.setEndTime(end);
        entry.setCategory(category);
        entry.setCountsAsWork(countsAsWork);
        entry.setNote(note);
        return entry;
    }

    private static void authenticate(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        username, "pw", List.of(new SimpleGrantedAuthority(role))));
    }
}
