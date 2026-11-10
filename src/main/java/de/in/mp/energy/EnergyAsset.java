package de.in.mp.energy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An energy investment asset (e.g. rooftop PV system, garden house PV, wallbox)
 * tracked for return on investment (ROI) and break-even amortization.
 */
@Entity
@Table(name = "energy_asset")
public class EnergyAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "installation_date", nullable = false)
    private LocalDate installationDate;

    @Column(name = "investment_cost", nullable = false, precision = 12, scale = 2)
    private BigDecimal investmentCost;

    @Column(name = "initial_amortized", nullable = false, precision = 12, scale = 2)
    private BigDecimal initialAmortized = BigDecimal.ZERO;

    @Column(name = "notes", length = 255)
    private String notes;

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

    public LocalDate getInstallationDate() {
        return installationDate;
    }

    public void setInstallationDate(LocalDate installationDate) {
        this.installationDate = installationDate;
    }

    public BigDecimal getInvestmentCost() {
        return investmentCost;
    }

    public void setInvestmentCost(BigDecimal investmentCost) {
        this.investmentCost = investmentCost;
    }

    public BigDecimal getInitialAmortized() {
        return initialAmortized;
    }

    public void setInitialAmortized(BigDecimal initialAmortized) {
        this.initialAmortized = initialAmortized;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}

