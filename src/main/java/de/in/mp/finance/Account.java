package de.in.mp.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
 * Primary finance account (checking, savings, loan or investment).
 * <p>
 * Monetary values are modelled as {@link BigDecimal} and mapped to
 * {@code NUMERIC(12, 2)} in PostgreSQL. {@code accountType} is mapped as a
 * plain string enum against the {@code VARCHAR(30)} column with a CHECK
 * constraint, mirroring the V3 approach for the security role column.
 * <p>
 * {@code currentBalance} mirrors the balance of the most recent
 * {@link AccountSnapshot} (or the initial balance while no snapshot exists).
 * {@code importKeys} holds statement identifiers confirmed by the user so
 * that statements without a matching IBAN resolve to this account again.
 */
@Entity
@Table(name = "finance_account")
public class Account {

    public static final String DEFAULT_CURRENCY = "EUR";
    public static final int DEFAULT_DISPLAY_ORDER = 100;

    private static final String IMPORT_KEY_SEPARATOR = ";";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 30)
    private AccountType accountType;

    @Column(name = "account_number", length = 50)
    private String accountNumber;

    @Column(name = "bank_name", length = 100)
    private String bankName;

    @Column(name = "account_holder", length = 100)
    private String accountHolder;

    @Column(name = "import_keys", length = 500)
    private String importKeys;

    @Column(name = "display_order", nullable = false)
    private int displayOrder = DEFAULT_DISPLAY_ORDER;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = DEFAULT_CURRENCY;

    @Column(name = "initial_balance", nullable = false, precision = 12, scale = 2)
    private BigDecimal initialBalance = BigDecimal.ZERO;

    @Column(name = "current_balance", nullable = false, precision = 12, scale = 2)
    private BigDecimal currentBalance = BigDecimal.ZERO;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "promotional_interest_rate", precision = 8, scale = 4)
    private BigDecimal promotionalInterestRate;

    @Column(name = "promotional_until")
    private LocalDate promotionalUntil;

    @Column(name = "subsequent_interest_rate", precision = 8, scale = 4)
    private BigDecimal subsequentInterestRate;

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
     * Confirmed statement identifiers of this account.
     */
    public Set<String> importKeySet() {
        if (importKeys == null || importKeys.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(importKeys.split(IMPORT_KEY_SEPARATOR))
                .map(String::trim)
                .filter(key -> !key.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public boolean hasImportKey(String key) {
        return key != null && importKeySet().contains(key);
    }

    public void addImportKey(String key) {
        if (key == null || key.isBlank() || hasImportKey(key)) {
            return;
        }
        Set<String> keys = new LinkedHashSet<>(importKeySet());
        keys.add(key.trim());
        importKeys = String.join(IMPORT_KEY_SEPARATOR, keys);
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getParentId() {
        return parentId;
    }

    public void setParentId(UUID parentId) {
        this.parentId = parentId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public void setAccountType(AccountType accountType) {
        this.accountType = accountType;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public void setAccountNumber(String accountNumber) {
        this.accountNumber = accountNumber;
    }

    public String getBankName() {
        return bankName;
    }

    public void setBankName(String bankName) {
        this.bankName = bankName;
    }

    public String getAccountHolder() {
        return accountHolder;
    }

    public void setAccountHolder(String accountHolder) {
        this.accountHolder = accountHolder;
    }

    public String getImportKeys() {
        return importKeys;
    }

    public void setImportKeys(String importKeys) {
        this.importKeys = importKeys;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public void setDisplayOrder(int displayOrder) {
        this.displayOrder = displayOrder;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public BigDecimal getInitialBalance() {
        return initialBalance;
    }

    public void setInitialBalance(BigDecimal initialBalance) {
        this.initialBalance = initialBalance;
    }

    public BigDecimal getCurrentBalance() {
        return currentBalance;
    }

    public void setCurrentBalance(BigDecimal currentBalance) {
        this.currentBalance = currentBalance;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public BigDecimal getPromotionalInterestRate() {
        return promotionalInterestRate;
    }

    public void setPromotionalInterestRate(BigDecimal promotionalInterestRate) {
        this.promotionalInterestRate = promotionalInterestRate;
    }

    public LocalDate getPromotionalUntil() {
        return promotionalUntil;
    }

    public void setPromotionalUntil(LocalDate promotionalUntil) {
        this.promotionalUntil = promotionalUntil;
    }

    public BigDecimal getSubsequentInterestRate() {
        return subsequentInterestRate;
    }

    public void setSubsequentInterestRate(BigDecimal subsequentInterestRate) {
        this.subsequentInterestRate = subsequentInterestRate;
    }

    /**
     * Resolves the interest rate currently in effect as of today.
     * For promotional terms, returns the promo rate while still within the
     * validity period; otherwise falls back to the subsequent interest rate.
     */
    public BigDecimal getCurrentInterestRate() {
        return getCurrentInterestRate(LocalDate.now());
    }

    public BigDecimal getCurrentInterestRate(LocalDate asOfDate) {
        if (accountType != AccountType.SAVINGS) {
            return null;
        }
        if (promotionalInterestRate != null) {
            if (promotionalUntil == null || asOfDate == null || !asOfDate.isAfter(promotionalUntil)) {
                return promotionalInterestRate;
            }
        }
        return subsequentInterestRate;
    }

    /**
     * Checks whether this savings account is subject to a low-interest warning
     * against the given threshold.
     */
    public boolean isInterestRateBelow(BigDecimal threshold) {
        return isInterestRateBelow(threshold, LocalDate.now());
    }

    public boolean isInterestRateBelow(BigDecimal threshold, LocalDate asOfDate) {
        if (accountType != AccountType.SAVINGS || threshold == null) {
            return false;
        }
        BigDecimal current = getCurrentInterestRate(asOfDate);
        if (current != null) {
            return current.compareTo(threshold) < 0;
        }
        if (promotionalInterestRate != null && promotionalUntil != null && asOfDate != null
                && asOfDate.isAfter(promotionalUntil)) {
            return true;
        }
        return false;
    }
}
