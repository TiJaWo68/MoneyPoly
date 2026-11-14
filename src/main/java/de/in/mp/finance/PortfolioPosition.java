package de.in.mp.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Single security holding (stock, ETF, fund) inside an {@link AccountType#INVESTMENT}
 * depot account. Used for position-based depot valuation (Mode A) without an order book.
 */
@Entity
@Table(name = "portfolio_position")
public class PortfolioPosition {

    public static final String DEFAULT_CURRENCY = "EUR";
    private static final int MONEY_SCALE = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "isin", length = 20)
    private String isin;

    @Column(name = "ticker", length = 32)
    private String ticker;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = DEFAULT_CURRENCY;

    @Column(name = "quantity", nullable = false, precision = 16, scale = 6)
    private BigDecimal quantity = BigDecimal.ZERO;

    @Column(name = "buy_in_price", precision = 14, scale = 4)
    private BigDecimal buyInPrice;

    @Column(name = "last_price", precision = 14, scale = 4)
    private BigDecimal lastPrice;

    @Column(name = "last_price_eur", precision = 14, scale = 4)
    private BigDecimal lastPriceEur;

    @Column(name = "fx_rate", precision = 14, scale = 6)
    private BigDecimal fxRate;

    @Column(name = "price_updated_at")
    private Instant priceUpdatedAt;

    @Column(name = "manual_price_override", nullable = false)
    private boolean manualPriceOverride;

    @Column(name = "notes", length = 255)
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (currency == null || currency.isBlank()) {
            currency = DEFAULT_CURRENCY;
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public void setAccountId(UUID accountId) {
        this.accountId = accountId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getIsin() {
        return isin;
    }

    public void setIsin(String isin) {
        this.isin = isin;
    }

    public String getTicker() {
        return ticker;
    }

    public void setTicker(String ticker) {
        this.ticker = ticker;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = (currency == null || currency.isBlank())
                ? DEFAULT_CURRENCY
                : currency.trim().toUpperCase();
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getBuyInPrice() {
        return buyInPrice;
    }

    public void setBuyInPrice(BigDecimal buyInPrice) {
        this.buyInPrice = buyInPrice;
    }

    public BigDecimal getLastPrice() {
        return lastPrice;
    }

    public void setLastPrice(BigDecimal lastPrice) {
        this.lastPrice = lastPrice;
    }

    public BigDecimal getLastPriceEur() {
        return lastPriceEur;
    }

    public void setLastPriceEur(BigDecimal lastPriceEur) {
        this.lastPriceEur = lastPriceEur;
    }

    public BigDecimal getFxRate() {
        return fxRate;
    }

    public void setFxRate(BigDecimal fxRate) {
        this.fxRate = fxRate;
    }

    public Instant getPriceUpdatedAt() {
        return priceUpdatedAt;
    }

    public void setPriceUpdatedAt(Instant priceUpdatedAt) {
        this.priceUpdatedAt = priceUpdatedAt;
    }

    public boolean isManualPriceOverride() {
        return manualPriceOverride;
    }

    public void setManualPriceOverride(boolean manualPriceOverride) {
        this.manualPriceOverride = manualPriceOverride;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * Effective unit price in EUR: uses {@link #lastPriceEur} when present,
     * otherwise falls back to {@link #lastPrice} for EUR-denominated holdings.
     */
    public Optional<BigDecimal> getEffectivePriceEur() {
        if (lastPriceEur != null) {
            return Optional.of(lastPriceEur);
        }
        if (lastPrice != null && DEFAULT_CURRENCY.equalsIgnoreCase(currency)) {
            return Optional.of(lastPrice);
        }
        return Optional.empty();
    }

    public Optional<BigDecimal> getMarketValueEur() {
        if (quantity == null) {
            return Optional.empty();
        }
        return getEffectivePriceEur()
                .map(price -> quantity.multiply(price).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
    }

    public Optional<BigDecimal> getCostBasisEur() {
        if (quantity == null || buyInPrice == null) {
            return Optional.empty();
        }
        return Optional.of(quantity.multiply(buyInPrice).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
    }

    public Optional<InvestmentPerformance> getPerformance() {
        return InvestmentPerformance.of(getMarketValueEur().orElse(null), getCostBasisEur().orElse(null));
    }

    public String getSymbolDisplay() {
        if (ticker != null && !ticker.isBlank() && isin != null && !isin.isBlank()) {
            return ticker + " (" + isin + ")";
        }
        if (ticker != null && !ticker.isBlank()) {
            return ticker;
        }
        return isin != null ? isin : "";
    }
}
