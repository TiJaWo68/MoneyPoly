package de.in.mp.worktime;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Immutable summary DTOs for daily and period work time analytics.
 */
public final class WorkTimeSummary {

    private WorkTimeSummary() {
    }

    public record ProfileOption(
            WorkTimeProfile profile,
            String username,
            String displayName) {
    }

    public record DaySummary(
            LocalDate date,
            List<WorkTimeEntry> entries,
            long workMinutes,
            long targetMinutes,
            long overtimeMinutes,
            long eveningMinutes,
            boolean overDailyLimit,
            boolean shortRestViolation,
            Long restMinutesSincePreviousDay,
            boolean weekend) {

        public long blockCount() {
            return entries.size();
        }
    }

    public record PeriodSummary(
            UUID profileId,
            LocalDate from,
            LocalDate to,
            long totalWorkMinutes,
            long targetMinutes,
            long overtimeMinutes,
            long schoolMinutes,
            long homeOfficeMinutes,
            long meetingMinutes,
            long otherMinutes,
            long eveningMinutes,
            long weekendMinutes,
            int daysOverDailyLimit,
            int shortRestCount,
            List<DaySummary> days) {
    }
}
