package de.in.mp.worktime;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.in.mp.finance.HouseholdService;
import de.in.mp.finance.Person;
import de.in.mp.finance.PersonRepository;
import de.in.mp.security.AppRole;
import de.in.mp.security.AppUser;
import de.in.mp.security.AppUserRepository;
import de.in.mp.worktime.WorkTimeSummary.DaySummary;
import de.in.mp.worktime.WorkTimeSummary.PeriodSummary;
import de.in.mp.worktime.WorkTimeSummary.ProfileOption;

/**
 * Coordinates per-user personal work time tracking, teacher pro-rata contract
 * calculations, overlap guards, health/rest warnings, and strict visibility
 * (each user only sees their own enabled profile; {@code ROLE_ADMIN} can manage
 * all enabled profiles).
 */
@Service
public class WorkTimeService {

    static final LocalTime EVENING_CUTOFF = LocalTime.of(18, 0);
    private static final DateTimeFormatter CSV_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final WorkTimeProfileRepository profileRepository;
    private final WorkTimeEntryRepository entryRepository;
    private final AppUserRepository userRepository;
    private final PersonRepository personRepository;
    private final HouseholdService householdService;

    public WorkTimeService(
            WorkTimeProfileRepository profileRepository,
            WorkTimeEntryRepository entryRepository,
            AppUserRepository userRepository) {
        this(profileRepository, entryRepository, userRepository, null, null);
    }

    @Autowired
    public WorkTimeService(
            WorkTimeProfileRepository profileRepository,
            WorkTimeEntryRepository entryRepository,
            AppUserRepository userRepository,
            @Autowired(required = false) PersonRepository personRepository,
            @Autowired(required = false) HouseholdService householdService) {
        this.profileRepository = profileRepository;
        this.entryRepository = entryRepository;
        this.userRepository = userRepository;
        this.personRepository = personRepository;
        this.householdService = householdService;
    }

    @Transactional(readOnly = true)
    public boolean canCurrentUserAccessWorkTime() {
        if (isDemoSession()) {
            return false;
        }
        Optional<AppUser> currentUser = resolveCurrentUser();
        if (currentUser.isEmpty()) {
            return false;
        }
        AppUser user = currentUser.get();
        if (isAdmin(user)) {
            return profileRepository.existsByEnabledTrue();
        }
        return user.getId() != null && profileRepository.existsByAppUserIdAndEnabledTrue(user.getId());
    }

    @Transactional(readOnly = true)
    public List<ProfileOption> getAccessibleProfilesForCurrentUser() {
        if (isDemoSession()) {
            return List.of();
        }
        Optional<AppUser> currentUser = resolveCurrentUser();
        if (currentUser.isEmpty()) {
            return List.of();
        }
        AppUser user = currentUser.get();
        List<WorkTimeProfile> profiles;
        if (isAdmin(user)) {
            profiles = profileRepository.findByEnabledTrueOrderByCreatedAtAsc();
        } else {
            if (user.getId() == null) {
                return List.of();
            }
            profiles = profileRepository.findByAppUserId(user.getId())
                    .filter(WorkTimeProfile::isEnabled)
                    .map(List::of)
                    .orElse(List.of());
        }
        if (profiles.isEmpty()) {
            return List.of();
        }
        Map<UUID, AppUser> usersById = userRepository.findAll().stream()
                .filter(u -> u.getId() != null)
                .collect(Collectors.toMap(AppUser::getId, u -> u, (a, b) -> a));
        Map<UUID, String> personNamesById = personRepository == null
                ? Map.of()
                : personRepository.findAll().stream()
                        .filter(p -> p.getId() != null && p.getName() != null)
                        .collect(Collectors.toMap(Person::getId, Person::getName, (a, b) -> a));

        List<ProfileOption> options = new ArrayList<>();
        for (WorkTimeProfile profile : profiles) {
            AppUser owner = usersById.get(profile.getAppUserId());
            String username = owner != null ? owner.getUsername() : "—";
            String personName = owner != null && owner.getPersonId() != null
                    ? personNamesById.get(owner.getPersonId())
                    : null;
            String display = personName != null && !personName.isBlank()
                    ? personName + " (" + username + ")"
                    : username;
            options.add(new ProfileOption(profile, username, display));
        }
        options.sort(Comparator.comparing(ProfileOption::displayName, String.CASE_INSENSITIVE_ORDER));
        return options;
    }

    @Transactional(readOnly = true)
    public Optional<WorkTimeProfile> findProfileForUser(UUID appUserId) {
        if (appUserId == null) {
            return Optional.empty();
        }
        return profileRepository.findByAppUserId(appUserId);
    }

    @Transactional(readOnly = true)
    public boolean isEnabledForUser(UUID appUserId) {
        return appUserId != null && profileRepository.existsByAppUserIdAndEnabledTrue(appUserId);
    }

    @Transactional
    public WorkTimeProfile saveProfileForUser(
            UUID appUserId,
            boolean enabled,
            BigDecimal fullTimeWeeklyHours,
            BigDecimal fullTimeTeachingUnits,
            BigDecimal contractTeachingUnits,
            int workDaysPerWeek,
            BigDecimal dailyWarningHours,
            BigDecimal minRestHours) {
        requireAdmin();
        if (appUserId == null) {
            throw new IllegalArgumentException("User ID is required");
        }
        validatePositive(fullTimeWeeklyHours, "Full-time weekly hours must be positive");
        validatePositive(fullTimeTeachingUnits, "Full-time teaching units must be positive");
        validatePositive(contractTeachingUnits, "Contract teaching units must be positive");
        if (workDaysPerWeek < 1 || workDaysPerWeek > 7) {
            throw new IllegalArgumentException("Work days per week must be between 1 and 7");
        }
        validatePositive(dailyWarningHours, "Daily warning threshold must be positive");
        if (minRestHours == null || minRestHours.signum() < 0) {
            throw new IllegalArgumentException("Minimum rest hours must not be negative");
        }

        WorkTimeProfile profile = profileRepository.findByAppUserId(appUserId).orElseGet(WorkTimeProfile::new);
        profile.setAppUserId(appUserId);
        profile.setEnabled(enabled);
        profile.setFullTimeWeeklyHours(fullTimeWeeklyHours);
        profile.setFullTimeTeachingUnits(fullTimeTeachingUnits);
        profile.setContractTeachingUnits(contractTeachingUnits);
        profile.setWorkDaysPerWeek(workDaysPerWeek);
        profile.setDailyWarningHours(dailyWarningHours);
        profile.setMinRestHours(minRestHours);
        return profileRepository.save(profile);
    }

    @Transactional(readOnly = true)
    public List<WorkTimeEntry> findEntriesForDay(UUID profileId, LocalDate date) {
        requireProfileAccess(profileId);
        if (date == null) {
            return List.of();
        }
        return entryRepository.findByProfileIdAndWorkDateOrderByStartTimeAsc(profileId, date);
    }

    @Transactional(readOnly = true)
    public Optional<LocalDate> findEarliestEntryDate(UUID profileId) {
        requireProfileAccess(profileId);
        List<WorkTimeEntry> all = entryRepository.findByProfileIdOrderByWorkDateDescStartTimeAsc(profileId);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1).getWorkDate());
    }

    @Transactional
    public WorkTimeEntry saveEntry(
            UUID profileId,
            UUID entryId,
            LocalDate workDate,
            LocalTime startTime,
            LocalTime endTime,
            WorkTimeCategory category,
            boolean countsAsWork,
            String note) {
        requireProfileAccess(profileId);
        if (workDate == null) {
            throw new IllegalArgumentException("Date is required");
        }
        if (startTime == null || endTime == null) {
            throw new IllegalArgumentException("Start and end time are required");
        }
        if (!endTime.isAfter(startTime)) {
            throw new IllegalArgumentException("End time must be after start time");
        }

        List<WorkTimeEntry> sameDay = entryRepository.findByProfileIdAndWorkDateOrderByStartTimeAsc(profileId, workDate);
        for (WorkTimeEntry existing : sameDay) {
            if (entryId != null && entryId.equals(existing.getId())) {
                continue;
            }
            if (startTime.isBefore(existing.getEndTime()) && existing.getStartTime().isBefore(endTime)) {
                throw new IllegalArgumentException("Time block overlaps with "
                        + existing.getStartTime().format(CSV_TIME) + "–" + existing.getEndTime().format(CSV_TIME));
            }
        }

        WorkTimeEntry entry;
        if (entryId != null) {
            entry = entryRepository.findById(entryId)
                    .orElseThrow(() -> new IllegalArgumentException("Entry not found: " + entryId));
            if (!profileId.equals(entry.getProfileId())) {
                throw new IllegalArgumentException("Entry does not belong to selected profile");
            }
        } else {
            entry = new WorkTimeEntry();
            entry.setProfileId(profileId);
        }
        entry.setWorkDate(workDate);
        entry.setStartTime(startTime);
        entry.setEndTime(endTime);
        entry.setCategory(category != null ? category : WorkTimeCategory.OTHER);
        entry.setCountsAsWork(countsAsWork);
        entry.setNote(note == null || note.isBlank() ? null : note.trim());
        return entryRepository.save(entry);
    }

    @Transactional
    public void deleteEntry(UUID entryId) {
        if (entryId == null) {
            return;
        }
        WorkTimeEntry entry = entryRepository.findById(entryId)
                .orElseThrow(() -> new IllegalArgumentException("Entry not found: " + entryId));
        requireProfileAccess(entry.getProfileId());
        entryRepository.delete(entry);
    }

    @Transactional
    public int copyDayBlocks(UUID profileId, LocalDate sourceDate, LocalDate targetDate, boolean schoolOnly) {
        requireProfileAccess(profileId);
        if (sourceDate == null || targetDate == null || sourceDate.equals(targetDate)) {
            return 0;
        }
        List<WorkTimeEntry> sourceEntries = entryRepository.findByProfileIdAndWorkDateOrderByStartTimeAsc(
                profileId, sourceDate);
        List<WorkTimeEntry> targetEntries = entryRepository.findByProfileIdAndWorkDateOrderByStartTimeAsc(
                profileId, targetDate);

        int copied = 0;
        for (WorkTimeEntry src : sourceEntries) {
            if (schoolOnly && src.getCategory() != WorkTimeCategory.SCHOOL) {
                continue;
            }
            boolean overlaps = targetEntries.stream().anyMatch(existing ->
                    src.getStartTime().isBefore(existing.getEndTime())
                            && existing.getStartTime().isBefore(src.getEndTime()));
            if (overlaps) {
                continue;
            }
            WorkTimeEntry clone = new WorkTimeEntry();
            clone.setProfileId(profileId);
            clone.setWorkDate(targetDate);
            clone.setStartTime(src.getStartTime());
            clone.setEndTime(src.getEndTime());
            clone.setCategory(src.getCategory());
            clone.setCountsAsWork(src.isCountsAsWork());
            clone.setNote(src.getNote());
            entryRepository.save(clone);
            targetEntries.add(clone);
            copied++;
        }
        return copied;
    }

    @Transactional(readOnly = true)
    public PeriodSummary summarizePeriod(WorkTimeProfile profile, LocalDate from, LocalDate to) {
        return summarizePeriod(profile, from, to, LocalDate.now());
    }

    @Transactional(readOnly = true)
    PeriodSummary summarizePeriod(WorkTimeProfile profile, LocalDate from, LocalDate to, LocalDate today) {
        if (profile == null || profile.getId() == null) {
            throw new IllegalArgumentException("Work time profile is required");
        }
        requireProfileAccess(profile.getId());
        LocalDate effectiveFrom = from != null ? from : today;
        LocalDate effectiveTo = to != null ? to : today;
        if (effectiveTo.isBefore(effectiveFrom)) {
            LocalDate swap = effectiveFrom;
            effectiveFrom = effectiveTo;
            effectiveTo = swap;
        }

        // Load one extra day before `effectiveFrom` so rest period violations on `effectiveFrom` are accurate
        List<WorkTimeEntry> fetched = entryRepository.findByProfileIdAndWorkDateBetweenOrderByWorkDateDescStartTimeAsc(
                profile.getId(), effectiveFrom.minusDays(1), effectiveTo);
        Map<LocalDate, List<WorkTimeEntry>> byDate = new TreeMap<>(Comparator.reverseOrder());
        for (WorkTimeEntry entry : fetched) {
            byDate.computeIfAbsent(entry.getWorkDate(), ignored -> new ArrayList<>()).add(entry);
        }

        long dailyTarget = profile.getPaidDailyMinutes();
        long dailyWarning = profile.getDailyWarningMinutes();
        long minRest = profile.getMinRestMinutes();

        long totalWork = 0L;
        long totalTarget = 0L;
        long school = 0L;
        long homeOffice = 0L;
        long meeting = 0L;
        long other = 0L;
        long commute = 0L;
        long commuteTrips = 0L;
        long evening = 0L;
        long weekend = 0L;
        int overLimitDays = 0;
        int shortRestCount = 0;

        LocalDate targetCutoff = effectiveTo.isBefore(today) ? effectiveTo : today;
        Map<LocalDate, DaySummary> daySummaries = new LinkedHashMap<>();

        for (LocalDate d = effectiveTo; !d.isBefore(effectiveFrom); d = d.minusDays(1)) {
            List<WorkTimeEntry> entries = byDate.getOrDefault(d, List.of());
            boolean isScheduled = profile.isScheduledWorkDay(d.getDayOfWeek());
            boolean countTarget = isScheduled && !d.isAfter(targetCutoff);
            long dayTarget = countTarget ? dailyTarget : 0L;
            totalTarget += dayTarget;

            if (entries.isEmpty()) {
                continue;
            }

            long dayWork = 0L;
            long dayCommute = 0L;
            long dayCommuteTrips = 0L;
            long dayEvening = 0L;

            for (WorkTimeEntry entry : entries) {
                long dur = entry.getDurationMinutes();
                if (entry.getCategory() == WorkTimeCategory.COMMUTE) {
                    commuteTrips++;
                    dayCommuteTrips++;
                    commute += dur;
                    dayCommute += dur;
                }
                if (entry.isCountsAsWork()) {
                    dayWork += dur;
                    long ev = entry.getMinutesAfter(EVENING_CUTOFF);
                    dayEvening += ev;
                    evening += ev;
                    switch (entry.getCategory()) {
                        case SCHOOL -> school += dur;
                        case HOME_OFFICE -> homeOffice += dur;
                        case MEETING -> meeting += dur;
                        case COMMUTE, OTHER -> other += dur;
                    }
                }
            }

            totalWork += dayWork;
            boolean isWeekend = !isScheduled;
            if (isWeekend) {
                weekend += dayWork;
            }
            boolean overLimit = dayWork > dailyWarning;
            if (overLimit) {
                overLimitDays++;
            }

            Long restMinutes = computeRestSincePreviousDay(entries, byDate.get(d.minusDays(1)));
            boolean shortRest = restMinutes != null && restMinutes < minRest;
            if (shortRest) {
                shortRestCount++;
            }

            daySummaries.put(d, new DaySummary(
                    d,
                    List.copyOf(entries),
                    dayWork,
                    dayTarget,
                    dayWork - dayTarget,
                    dayCommute,
                    dayCommuteTrips,
                    dayEvening,
                    overLimit,
                    shortRest,
                    restMinutes,
                    isWeekend));
        }

        return new PeriodSummary(
                profile.getId(),
                effectiveFrom,
                effectiveTo,
                totalWork,
                totalTarget,
                totalWork - totalTarget,
                school,
                homeOffice,
                meeting,
                other,
                commute,
                commuteTrips,
                evening,
                weekend,
                overLimitDays,
                shortRestCount,
                List.copyOf(daySummaries.values()));
    }

    @Transactional(readOnly = true)
    public DaySummary summarizeDay(WorkTimeProfile profile, LocalDate date) {
        PeriodSummary period = summarizePeriod(profile, date, date, date);
        if (!period.days().isEmpty()) {
            return period.days().get(0);
        }
        long target = profile.isScheduledWorkDay(date.getDayOfWeek()) ? profile.getPaidDailyMinutes() : 0L;
        return new DaySummary(
                date, List.of(), 0L, target, -target, 0L, 0L, 0L, false, false, null,
                !profile.isScheduledWorkDay(date.getDayOfWeek()));
    }

    @Transactional(readOnly = true)
    public String buildCsvExport(WorkTimeProfile profile, LocalDate from, LocalDate to) {
        PeriodSummary summary = summarizePeriod(profile, from, to);
        StringBuilder sb = new StringBuilder();
        sb.append("Datum;Start;Ende;Dauer_Min;Kategorie;Zaehlt_als_Arbeitszeit;Notiz\n");
        for (int i = summary.days().size() - 1; i >= 0; i--) {
            DaySummary day = summary.days().get(i);
            for (WorkTimeEntry entry : day.entries()) {
                sb.append(CSV_DATE.format(entry.getWorkDate())).append(';')
                        .append(CSV_TIME.format(entry.getStartTime())).append(';')
                        .append(CSV_TIME.format(entry.getEndTime())).append(';')
                        .append(entry.getDurationMinutes()).append(';')
                        .append(entry.getCategory().name()).append(';')
                        .append(entry.isCountsAsWork() ? "ja" : "nein").append(';')
                        .append(escapeCsv(entry.getNote())).append('\n');
            }
        }
        return sb.toString();
    }

    public static String formatMinutes(long totalMinutes) {
        long abs = Math.abs(totalMinutes);
        long hours = abs / 60;
        long mins = abs % 60;
        String sign = totalMinutes < 0 ? "-" : "";
        return String.format("%s%dh %02dm", sign, hours, mins);
    }

    public static String formatSignedMinutes(long totalMinutes) {
        return totalMinutes > 0 ? "+" + formatMinutes(totalMinutes) : formatMinutes(totalMinutes);
    }

    private static Long computeRestSincePreviousDay(List<WorkTimeEntry> todayEntries, List<WorkTimeEntry> prevEntries) {
        if (todayEntries == null || todayEntries.isEmpty() || prevEntries == null || prevEntries.isEmpty()) {
            return null;
        }
        LocalTime firstStart = todayEntries.stream()
                .filter(WorkTimeEntry::isCountsAsWork)
                .map(WorkTimeEntry::getStartTime)
                .min(LocalTime::compareTo)
                .orElse(null);
        LocalTime prevLastEnd = prevEntries.stream()
                .filter(WorkTimeEntry::isCountsAsWork)
                .map(WorkTimeEntry::getEndTime)
                .max(LocalTime::compareTo)
                .orElse(null);
        if (firstStart == null || prevLastEnd == null) {
            return null;
        }
        LocalDate d = todayEntries.get(0).getWorkDate();
        return Duration.between(d.minusDays(1).atTime(prevLastEnd), d.atTime(firstStart)).toMinutes();
    }

    private static void validatePositive(BigDecimal value, String message) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(message);
        }
    }

    private static String escapeCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String sanitized = raw.replace("\r", " ").replace("\n", " ");
        if (sanitized.contains(";") || sanitized.contains("\"")) {
            return "\"" + sanitized.replace("\"", "\"\"") + "\"";
        }
        return sanitized;
    }

    private void requireAdmin() {
        AppUser user = resolveCurrentUser()
                .orElseThrow(() -> new IllegalStateException("Authentication required"));
        if (!isAdmin(user)) {
            throw new IllegalStateException("Administrator privileges required");
        }
    }

    private void requireProfileAccess(UUID profileId) {
        if (profileId == null || isDemoSession()) {
            throw new IllegalStateException("Work time tracking is not available");
        }
        AppUser user = resolveCurrentUser()
                .orElseThrow(() -> new IllegalStateException("Authentication required"));
        WorkTimeProfile profile = profileRepository.findById(profileId)
                .orElseThrow(() -> new IllegalArgumentException("Work time profile not found: " + profileId));
        if (!profile.isEnabled()) {
            throw new IllegalStateException("Work time profile is disabled");
        }
        if (!isAdmin(user) && !profile.getAppUserId().equals(user.getId())) {
            throw new IllegalStateException("Access denied to another user's work time profile");
        }
    }

    private boolean isDemoSession() {
        return householdService != null && householdService.isDemoUser();
    }

    private static boolean isAdmin(AppUser user) {
        return user != null && user.getRoles() != null && user.getRoles().contains(AppRole.ROLE_ADMIN);
    }

    private Optional<AppUser> resolveCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
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
