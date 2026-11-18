package de.in.mp.finance.banksync;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.OptionalLong;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * Persisted Open-Banking AIS connection for a household.
 * <p>
 * Stores no bank passwords or PINs; only encrypted external session metadata
 * and consent validity timestamps.
 */
@Entity
@Table(name = "bank_connection")
public class BankConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(name = "provider_id", nullable = false, length = 40)
    private String providerId;

    @Column(name = "institution_id", length = 120)
    private String institutionId;

    @Column(name = "institution_name", nullable = false, length = 120)
    private String institutionName;

    @Column(name = "encrypted_session_payload", columnDefinition = "text")
    private String encryptedSessionPayload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private BankConnectionStatus status = BankConnectionStatus.PENDING;

    @Column(name = "consent_expires_at")
    private OffsetDateTime consentExpiresAt;

    @Column(name = "last_sync_at")
    private OffsetDateTime lastSyncAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touchUpdatedAt() {
        this.updatedAt = OffsetDateTime.now();
    }

    public OptionalLong getRemainingConsentDays(OffsetDateTime asOf) {
        if (consentExpiresAt == null || asOf == null) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(ChronoUnit.DAYS.between(asOf, consentExpiresAt));
    }

    public boolean isConsentExpired(OffsetDateTime asOf) {
        if (status == BankConnectionStatus.EXPIRED) {
            return true;
        }
        return consentExpiresAt != null && asOf != null && !consentExpiresAt.isAfter(asOf);
    }

    public boolean isConsentExpiringSoon(OffsetDateTime asOf, int warningDays) {
        if (consentExpiresAt == null || asOf == null || isConsentExpired(asOf)) {
            return false;
        }
        return ChronoUnit.DAYS.between(asOf, consentExpiresAt) <= warningDays;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getHouseholdId() {
        return householdId;
    }

    public void setHouseholdId(UUID householdId) {
        this.householdId = householdId;
    }

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public String getInstitutionId() {
        return institutionId;
    }

    public void setInstitutionId(String institutionId) {
        this.institutionId = institutionId;
    }

    public String getInstitutionName() {
        return institutionName;
    }

    public void setInstitutionName(String institutionName) {
        this.institutionName = institutionName;
    }

    public String getEncryptedSessionPayload() {
        return encryptedSessionPayload;
    }

    public void setEncryptedSessionPayload(String encryptedSessionPayload) {
        this.encryptedSessionPayload = encryptedSessionPayload;
    }

    public BankConnectionStatus getStatus() {
        return status;
    }

    public void setStatus(BankConnectionStatus status) {
        this.status = status;
    }

    public OffsetDateTime getConsentExpiresAt() {
        return consentExpiresAt;
    }

    public void setConsentExpiresAt(OffsetDateTime consentExpiresAt) {
        this.consentExpiresAt = consentExpiresAt;
    }

    public OffsetDateTime getLastSyncAt() {
        return lastSyncAt;
    }

    public void setLastSyncAt(OffsetDateTime lastSyncAt) {
        this.lastSyncAt = lastSyncAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
