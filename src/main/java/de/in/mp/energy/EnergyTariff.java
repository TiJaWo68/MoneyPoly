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
 * Time-bounded price or remuneration tariff for a specific {@link MeterType}
 * (e.g. grid import price, EEG feed-in tariff, employer EV charging rate,
 * gas/water tariff).
 */
@Entity
@Table(name = "energy_tariff")
public class EnergyTariff {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "for_meter_type", nullable = false, length = 30)
    private MeterType forMeterType;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "unit_price_eur", nullable = false, precision = 8, scale = 4)
    private BigDecimal unitPriceEur;

    @Column(name = "monthly_base_fee_eur", nullable = false, precision = 8, scale = 2)
    private BigDecimal monthlyBaseFeeEur = BigDecimal.ZERO;

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

    public MeterType getForMeterType() {
        return forMeterType;
    }

    public void setForMeterType(MeterType forMeterType) {
        this.forMeterType = forMeterType;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(LocalDate validFrom) {
        this.validFrom = validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
    }

    public void setValidTo(LocalDate validTo) {
        this.validTo = validTo;
    }

    public BigDecimal getUnitPriceEur() {
        return unitPriceEur;
    }

    public void setUnitPriceEur(BigDecimal unitPriceEur) {
        this.unitPriceEur = unitPriceEur;
    }

    public BigDecimal getMonthlyBaseFeeEur() {
        return monthlyBaseFeeEur;
    }

    public void setMonthlyBaseFeeEur(BigDecimal monthlyBaseFeeEur) {
        this.monthlyBaseFeeEur = monthlyBaseFeeEur;
    }
}

