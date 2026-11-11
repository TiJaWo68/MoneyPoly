package de.in.mp.importer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import de.in.mp.finance.AccountType;
import de.in.mp.finance.SnapshotValue;

/**
 * One account column of the legacy finance spreadsheet together with the
 * user-editable import settings shown in the migration dialog.
 * <p>
 * The structural part (sheet, column, headers, values) is fixed by the
 * spreadsheet; the settings part (include flag, target names, master data,
 * merge target) is edited by the user before the import is executed.
 */
public class OdsAccountColumn {

    private final String sheetName;
    private final int columnIndex;
    private final String institutionHeader;
    private final String subHeader;
    private final List<SnapshotValue> values;

    private boolean include = true;
    private String institutionName;
    private String accountName;
    private AccountType accountType;
    private String accountNumber;
    private String bankName;
    private String accountHolder;
    private boolean active;
    private UUID existingAccountId;
    private OdsAccountColumn mergeInto;

    public OdsAccountColumn(String sheetName, int columnIndex, String institutionHeader, String subHeader,
            List<SnapshotValue> values) {
        this.sheetName = sheetName;
        this.columnIndex = columnIndex;
        this.institutionHeader = institutionHeader == null ? "" : institutionHeader.trim();
        this.subHeader = subHeader == null ? "" : subHeader.trim();
        this.values = List.copyOf(values);
    }

    /**
     * Label as shown in the spreadsheet, e.g. "Bank A / Giro".
     */
    public String sourceLabel() {
        if (institutionHeader.isEmpty()) {
            return subHeader;
        }
        return subHeader.isEmpty() ? institutionHeader : institutionHeader + " / " + subHeader;
    }

    public SnapshotValue latestValue() {
        return values.stream().max(Comparator.comparing(SnapshotValue::date)).orElse(null);
    }

    public LocalDate latestDate() {
        SnapshotValue latest = latestValue();
        return latest == null ? null : latest.date();
    }

    public BigDecimal latestBalance() {
        SnapshotValue latest = latestValue();
        return latest == null ? null : latest.balance();
    }

    public LocalDate earliestDate() {
        return values.stream().map(SnapshotValue::date).min(Comparator.naturalOrder()).orElse(null);
    }

    /**
     * Whether the account is grouped below an institution node.
     */
    public boolean grouped() {
        return institutionName != null && !institutionName.isBlank();
    }

    public String sheetName() {
        return sheetName;
    }

    public int columnIndex() {
        return columnIndex;
    }

    public String institutionHeader() {
        return institutionHeader;
    }

    public String subHeader() {
        return subHeader;
    }

    public List<SnapshotValue> values() {
        return values;
    }

    public boolean isInclude() {
        return include;
    }

    public void setInclude(boolean include) {
        this.include = include;
    }

    public String getInstitutionName() {
        return institutionName;
    }

    public void setInstitutionName(String institutionName) {
        this.institutionName = institutionName;
    }

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
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

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public UUID getExistingAccountId() {
        return existingAccountId;
    }

    public void setExistingAccountId(UUID existingAccountId) {
        this.existingAccountId = existingAccountId;
    }

    public OdsAccountColumn getMergeInto() {
        return mergeInto;
    }

    public void setMergeInto(OdsAccountColumn mergeInto) {
        this.mergeInto = mergeInto == this ? null : mergeInto;
    }
}
