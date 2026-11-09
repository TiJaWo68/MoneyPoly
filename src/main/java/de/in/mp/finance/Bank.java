package de.in.mp.finance;

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
 * Cached German bank master data keyed by Bankleitzahl (BLZ). Populated from
 * OpenIBAN on first lookup so that subsequent lookups work offline.
 */
@Entity
@Table(name = "bank")
public class Bank {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "blz", nullable = false, unique = true, length = 8)
    private String blz;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "bic", length = 11)
    private String bic;

    @Column(name = "zip", length = 10)
    private String zip;

    @Column(name = "city", length = 100)
    private String city;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touchUpdatedAt() {
        this.updatedAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getBlz() {
        return blz;
    }

    public void setBlz(String blz) {
        this.blz = blz;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBic() {
        return bic;
    }

    public void setBic(String bic) {
        this.bic = bic;
    }

    public String getZip() {
        return zip;
    }

    public void setZip(String zip) {
        this.zip = zip;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
