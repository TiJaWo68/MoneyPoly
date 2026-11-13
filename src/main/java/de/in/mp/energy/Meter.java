package de.in.mp.energy;

import java.math.BigDecimal;
import java.time.LocalDate;
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
 * Physical or virtual household meter (water, gas, electricity import/export,
 * PV generation, wallbox charging).
 * <p>
 * Supports optional mechanical overflow thresholds (e.g. {@code 100000} for a
 * 5-digit sub-meter) and conversion factors (e.g. gas m3 to kWh).
 */
@Entity
@Table(name = "meter")
public class Meter {

    private static final String DEFAULT_UNIT = "kWh";
    private static final int DEFAULT_DISPLAY_ORDER = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "serial_number", length = 50)
    private String serialNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private MeterType type;

    @Column(name = "unit", nullable = false, length = 10)
    private String unit = DEFAULT_UNIT;

    @Column(name = "conversion_factor", nullable = false, precision = 8, scale = 4)
    private BigDecimal conversionFactor = BigDecimal.ONE;

    @Column(name = "overflow_threshold", precision = 12, scale = 3)
    private BigDecimal overflowThreshold;

    @Column(name = "display_order", nullable = false)
    private int displayOrder = DEFAULT_DISPLAY_ORDER;

    @Column(name = "asset_id")
    private UUID assetId;

    @Column(name = "installed_at")
    private LocalDate installedAt;

    @Column(name = "replaced_at")
    private LocalDate replacedAt;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "recognition_hint", length = 255)
    private String recognitionHint;

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

    public String getSerialNumber() {
        return serialNumber;
    }

    public void setSerialNumber(String serialNumber) {
        this.serialNumber = serialNumber;
    }

    public MeterType getType() {
        return type;
    }

    public void setType(MeterType type) {
        this.type = type;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public BigDecimal getConversionFactor() {
        return conversionFactor;
    }

    public void setConversionFactor(BigDecimal conversionFactor) {
        this.conversionFactor = conversionFactor;
    }

    public BigDecimal getOverflowThreshold() {
        return overflowThreshold;
    }

    public void setOverflowThreshold(BigDecimal overflowThreshold) {
        this.overflowThreshold = overflowThreshold;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public void setDisplayOrder(int displayOrder) {
        this.displayOrder = displayOrder;
    }

    public UUID getAssetId() {
        return assetId;
    }

    public void setAssetId(UUID assetId) {
        this.assetId = assetId;
    }

    public LocalDate getInstalledAt() {
        return installedAt;
    }

    public void setInstalledAt(LocalDate installedAt) {
        this.installedAt = installedAt;
    }

    public LocalDate getReplacedAt() {
        return replacedAt;
    }

    public void setReplacedAt(LocalDate replacedAt) {
        this.replacedAt = replacedAt;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getRecognitionHint() {
        return recognitionHint;
    }

    public void setRecognitionHint(String recognitionHint) {
        this.recognitionHint = recognitionHint;
    }
}

