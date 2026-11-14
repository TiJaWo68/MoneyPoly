package de.in.mp.finance;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Isolated finance and private-loan tenant (e.g. a family household or the
 * synthetic demo household).
 */
@Entity
@Table(name = "household")
public class Household {

    public static final BigDecimal DEFAULT_WARNING_THRESHOLD = new BigDecimal("2.00");

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name", nullable = false, unique = true, length = 100)
    private String name;

    @Column(name = "is_demo", nullable = false)
    private boolean demo = false;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "dashboard_warning_threshold", nullable = false, precision = 5, scale = 2)
    private BigDecimal dashboardWarningThreshold = DEFAULT_WARNING_THRESHOLD;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isDemo() {
        return demo;
    }

    public void setDemo(boolean demo) {
        this.demo = demo;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public BigDecimal getDashboardWarningThreshold() {
        return dashboardWarningThreshold;
    }

    public void setDashboardWarningThreshold(BigDecimal dashboardWarningThreshold) {
        this.dashboardWarningThreshold = dashboardWarningThreshold;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
