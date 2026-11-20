package de.in.mp.worktime;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * Per-user work time configuration supporting pro-rata teacher teaching load
 * contracts (e.g. 20 / 27 teaching units scaled to a 40 h full-time week).
 */
@Entity
@Table(name = "work_time_profile")
public class WorkTimeProfile {

    public static final BigDecimal DEFAULT_FULL_TIME_WEEKLY_HOURS = new BigDecimal("40.00");
    public static final BigDecimal DEFAULT_FULL_TIME_TEACHING_UNITS = new BigDecimal("27.00");
    public static final BigDecimal DEFAULT_CONTRACT_TEACHING_UNITS = new BigDecimal("20.00");
    public static final int DEFAULT_WORK_DAYS_PER_WEEK = 5;
    public static final BigDecimal DEFAULT_DAILY_WARNING_HOURS = new BigDecimal("10.00");
    public static final BigDecimal DEFAULT_MIN_REST_HOURS = new BigDecimal("11.00");

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "app_user_id", nullable = false, unique = true)
    private UUID appUserId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = false;

    @Column(name = "full_time_weekly_hours", nullable = false, precision = 5, scale = 2)
    private BigDecimal fullTimeWeeklyHours = DEFAULT_FULL_TIME_WEEKLY_HOURS;

    @Column(name = "full_time_teaching_units", nullable = false, precision = 5, scale = 2)
    private BigDecimal fullTimeTeachingUnits = DEFAULT_FULL_TIME_TEACHING_UNITS;

    @Column(name = "contract_teaching_units", nullable = false, precision = 5, scale = 2)
    private BigDecimal contractTeachingUnits = DEFAULT_CONTRACT_TEACHING_UNITS;

    @Column(name = "work_days_per_week", nullable = false)
    private int workDaysPerWeek = DEFAULT_WORK_DAYS_PER_WEEK;

    @Column(name = "daily_warning_hours", nullable = false, precision = 5, scale = 2)
    private BigDecimal dailyWarningHours = DEFAULT_DAILY_WARNING_HOURS;

    @Column(name = "min_rest_hours", nullable = false, precision = 5, scale = 2)
    private BigDecimal minRestHours = DEFAULT_MIN_REST_HOURS;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touchUpdatedAt() {
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * Pro-rata employment factor ({@code contractTeachingUnits / fullTimeTeachingUnits}).
     */
    public BigDecimal getPartTimeRatio() {
        if (fullTimeTeachingUnits == null || fullTimeTeachingUnits.signum() <= 0
                || contractTeachingUnits == null || contractTeachingUnits.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return contractTeachingUnits.divide(fullTimeTeachingUnits, 6, RoundingMode.HALF_UP);
    }

    /**
     * Paid weekly target hours according to contract ratio.
     */
    public BigDecimal getPaidWeeklyHours() {
        if (fullTimeWeeklyHours == null || fullTimeWeeklyHours.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return fullTimeWeeklyHours.multiply(getPartTimeRatio()).setScale(2, RoundingMode.HALF_UP);
    }

    public long getPaidWeeklyMinutes() {
        if (fullTimeWeeklyHours == null || fullTimeWeeklyHours.signum() <= 0) {
            return 0L;
        }
        BigDecimal minutes = fullTimeWeeklyHours
                .multiply(getPartTimeRatio())
                .multiply(BigDecimal.valueOf(60));
        return minutes.setScale(0, RoundingMode.HALF_UP).longValue();
    }

    public long getPaidDailyMinutes() {
        int days = Math.max(1, Math.min(7, workDaysPerWeek));
        return Math.round((double) getPaidWeeklyMinutes() / days);
    }

    public boolean isScheduledWorkDay(DayOfWeek dayOfWeek) {
        int days = Math.max(1, Math.min(7, workDaysPerWeek));
        return dayOfWeek != null && dayOfWeek.getValue() <= days;
    }

    public long getDailyWarningMinutes() {
        BigDecimal hours = dailyWarningHours != null ? dailyWarningHours : DEFAULT_DAILY_WARNING_HOURS;
        return hours.multiply(BigDecimal.valueOf(60)).setScale(0, RoundingMode.HALF_UP).longValue();
    }

    public long getMinRestMinutes() {
        BigDecimal hours = minRestHours != null ? minRestHours : DEFAULT_MIN_REST_HOURS;
        return hours.multiply(BigDecimal.valueOf(60)).setScale(0, RoundingMode.HALF_UP).longValue();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getAppUserId() {
        return appUserId;
    }

    public void setAppUserId(UUID appUserId) {
        this.appUserId = appUserId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public BigDecimal getFullTimeWeeklyHours() {
        return fullTimeWeeklyHours;
    }

    public void setFullTimeWeeklyHours(BigDecimal fullTimeWeeklyHours) {
        this.fullTimeWeeklyHours = fullTimeWeeklyHours;
    }

    public BigDecimal getFullTimeTeachingUnits() {
        return fullTimeTeachingUnits;
    }

    public void setFullTimeTeachingUnits(BigDecimal fullTimeTeachingUnits) {
        this.fullTimeTeachingUnits = fullTimeTeachingUnits;
    }

    public BigDecimal getContractTeachingUnits() {
        return contractTeachingUnits;
    }

    public void setContractTeachingUnits(BigDecimal contractTeachingUnits) {
        this.contractTeachingUnits = contractTeachingUnits;
    }

    public int getWorkDaysPerWeek() {
        return workDaysPerWeek;
    }

    public void setWorkDaysPerWeek(int workDaysPerWeek) {
        this.workDaysPerWeek = workDaysPerWeek;
    }

    public BigDecimal getDailyWarningHours() {
        return dailyWarningHours;
    }

    public void setDailyWarningHours(BigDecimal dailyWarningHours) {
        this.dailyWarningHours = dailyWarningHours;
    }

    public BigDecimal getMinRestHours() {
        return minRestHours;
    }

    public void setMinRestHours(BigDecimal minRestHours) {
        this.minRestHours = minRestHours;
    }
}
