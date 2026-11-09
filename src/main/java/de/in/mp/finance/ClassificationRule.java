package de.in.mp.finance;

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
 * Rule for the fully automatic categorization of imported bank transactions.
 * <p>
 * The first matching rule (lowest {@code priority}) assigns the configured
 * flow, category and loan targets. Rules are evaluated in priority order.
 */
@Entity
@Table(name = "classification_rule")
public class ClassificationRule {

    public static final int DEFAULT_PRIORITY = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "priority", nullable = false)
    private int priority = DEFAULT_PRIORITY;

    @Column(name = "match_iban", length = 34)
    private String matchIban;

    @Column(name = "match_counterparty_regex", length = 255)
    private String matchCounterpartyRegex;

    @Column(name = "match_purpose_regex", length = 255)
    private String matchPurposeRegex;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_flow", nullable = false, length = 30)
    private FlowType targetFlow;

    @Column(name = "target_category_id")
    private UUID targetCategoryId;

    @Column(name = "target_loan_id")
    private UUID targetLoanId;

    @Column(name = "flag_for_review", nullable = false)
    private boolean flagForReview = false;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public String getMatchIban() {
        return matchIban;
    }

    public void setMatchIban(String matchIban) {
        this.matchIban = matchIban;
    }

    public String getMatchCounterpartyRegex() {
        return matchCounterpartyRegex;
    }

    public void setMatchCounterpartyRegex(String matchCounterpartyRegex) {
        this.matchCounterpartyRegex = matchCounterpartyRegex;
    }

    public String getMatchPurposeRegex() {
        return matchPurposeRegex;
    }

    public void setMatchPurposeRegex(String matchPurposeRegex) {
        this.matchPurposeRegex = matchPurposeRegex;
    }

    public FlowType getTargetFlow() {
        return targetFlow;
    }

    public void setTargetFlow(FlowType targetFlow) {
        this.targetFlow = targetFlow;
    }

    public UUID getTargetCategoryId() {
        return targetCategoryId;
    }

    public void setTargetCategoryId(UUID targetCategoryId) {
        this.targetCategoryId = targetCategoryId;
    }

    public UUID getTargetLoanId() {
        return targetLoanId;
    }

    public void setTargetLoanId(UUID targetLoanId) {
        this.targetLoanId = targetLoanId;
    }

    public boolean isFlagForReview() {
        return flagForReview;
    }

    public void setFlagForReview(boolean flagForReview) {
        this.flagForReview = flagForReview;
    }
}
