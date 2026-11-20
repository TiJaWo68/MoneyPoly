package de.in.mp.worktime;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Single continuous work or commute block on a specific {@link LocalDate}.
 * Multiple blocks per day are supported cleanly (e.g. morning classes, afternoon
 * prep at home, evening parent conference or grading).
 */
@Entity
@Table(name = "work_time_entry")
public class WorkTimeEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "profile_id", nullable = false)
    private UUID profileId;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30)
    private WorkTimeCategory category = WorkTimeCategory.SCHOOL;

    @Column(name = "counts_as_work", nullable = false)
    private boolean countsAsWork = true;

    @Column(name = "note", length = 255)
    private String note;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public long getDurationMinutes() {
        if (startTime == null || endTime == null || !endTime.isAfter(startTime)) {
            return 0L;
        }
        return ChronoUnit.MINUTES.between(startTime, endTime);
    }

    public long getMinutesAfter(LocalTime cutoff) {
        if (!countsAsWork || cutoff == null || startTime == null || endTime == null
                || !endTime.isAfter(startTime) || !endTime.isAfter(cutoff)) {
            return 0L;
        }
        LocalTime effectiveStart = startTime.isAfter(cutoff) ? startTime : cutoff;
        return ChronoUnit.MINUTES.between(effectiveStart, endTime);
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getProfileId() {
        return profileId;
    }

    public void setProfileId(UUID profileId) {
        this.profileId = profileId;
    }

    public LocalDate getWorkDate() {
        return workDate;
    }

    public void setWorkDate(LocalDate workDate) {
        this.workDate = workDate;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalTime startTime) {
        this.startTime = startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalTime endTime) {
        this.endTime = endTime;
    }

    public WorkTimeCategory getCategory() {
        return category;
    }

    public void setCategory(WorkTimeCategory category) {
        this.category = category;
    }

    public boolean isCountsAsWork() {
        return countsAsWork;
    }

    public void setCountsAsWork(boolean countsAsWork) {
        this.countsAsWork = countsAsWork;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
