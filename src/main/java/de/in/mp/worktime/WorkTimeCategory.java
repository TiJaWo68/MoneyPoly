package de.in.mp.worktime;

/**
 * Categories of work time blocks for teachers and split-shift schedules.
 */
public enum WorkTimeCategory {

    SCHOOL(true),
    HOME_OFFICE(true),
    MEETING(true),
    OTHER(true);

    private final boolean defaultCountsAsWork;

    WorkTimeCategory(boolean defaultCountsAsWork) {
        this.defaultCountsAsWork = defaultCountsAsWork;
    }

    public boolean isDefaultCountsAsWork() {
        return defaultCountsAsWork;
    }
}
