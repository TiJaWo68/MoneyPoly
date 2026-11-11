package de.in.mp.importer;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.in.mp.admin.DatabaseResetService;
import de.in.mp.finance.Account;
import de.in.mp.finance.AccountService;
import de.in.mp.finance.AccountSnapshotService;
import de.in.mp.finance.AccountType;
import de.in.mp.finance.SnapshotValue;
import de.in.mp.loans.LoanContract;
import de.in.mp.loans.LoanContractService;

/**
 * One-time migration of the legacy finance spreadsheet.
 * <p>
 * {@link #analyze} parses the spreadsheet and pre-fills the import settings
 * of every account column: columns that correspond to private loan contracts
 * (managed in the loans module) are excluded, columns matching an existing
 * account are linked to it. {@link #execute} creates institution nodes and
 * accounts as configured and records the complete balance history. Running
 * the migration twice is idempotent because snapshots are upserted per
 * (account, date) and accounts are reused by institution and name.
 */
@Service
public class FinanceOdsImportService {

    private static final int DISPLAY_ORDER_STEP = 10;

    private final AccountService accountService;
    private final AccountSnapshotService snapshotService;
    private final LoanContractService loanContractService;
    private final DatabaseResetService databaseResetService;

    public FinanceOdsImportService(AccountService accountService, AccountSnapshotService snapshotService,
            LoanContractService loanContractService) {
        this(accountService, snapshotService, loanContractService, null);
    }

    @Autowired
    public FinanceOdsImportService(AccountService accountService, AccountSnapshotService snapshotService,
            LoanContractService loanContractService, DatabaseResetService databaseResetService) {
        this.accountService = accountService;
        this.snapshotService = snapshotService;
        this.loanContractService = loanContractService;
        this.databaseResetService = databaseResetService;
    }

    @Transactional(readOnly = true)
    public List<OdsAccountColumn> analyze(InputStream odsStream) {
        List<OdsAccountColumn> columns = FinanceOdsParser.parse(odsStream);
        Set<String> loanDesignations = loanContractService.findAll().stream()
                .map(LoanContract::getDesignation)
                .filter(Objects::nonNull)
                .map(FinanceOdsImportService::normalize)
                .collect(Collectors.toSet());
        List<Account> accounts = accountService.findAll();

        for (OdsAccountColumn column : columns) {
            if (matchesLoanDesignation(column.subHeader(), loanDesignations)) {
                column.setInclude(false);
            }
            findExisting(column, accounts).ifPresent(account -> linkExisting(column, account));
        }
        return columns;
    }

    private static boolean matchesLoanDesignation(String subHeader, Set<String> loanDesignations) {
        String normalizedHeader = normalize(subHeader);
        if (normalizedHeader.isEmpty()) {
            return false;
        }
        if (loanDesignations.contains(normalizedHeader)) {
            return true;
        }
        return loanDesignations.stream()
                .flatMap(designation -> Arrays.stream(designation.split("[^\\p{L}\\p{N}]+")))
                .filter(token -> !token.isEmpty())
                .anyMatch(normalizedHeader::equals);
    }

    @Transactional
    public OdsMigrationResult execute(List<OdsAccountColumn> columns) {
        if (databaseResetService != null) {
            databaseResetService.resetFinanceData();
            columns.forEach(column -> column.setExistingAccountId(null));
        }

        Map<String, Account> institutions = new LinkedHashMap<>();
        accountService.findAll().stream()
                .filter(account -> account.getAccountType() == AccountType.INSTITUTION)
                .forEach(account -> institutions.putIfAbsent(normalize(account.getName()), account));

        int accountsCreated = 0;
        int snapshotsRecorded = 0;
        int order = 0;
        for (OdsAccountColumn column : columns) {
            if (!column.isInclude() || mergeTarget(column).isPresent()) {
                continue;
            }
            order += DISPLAY_ORDER_STEP;
            UUID parentId = column.grouped() ? ensureInstitution(column, institutions).getId() : null;
            boolean created = column.getExistingAccountId() == null;
            Account account = saveAccount(column, parentId, order);
            if (created) {
                accountsCreated++;
            }
            snapshotsRecorded += snapshotService.recordSnapshots(account.getId(), collectValues(column, columns));
        }
        return new OdsMigrationResult(institutions.size(), accountsCreated, snapshotsRecorded);
    }

    /**
     * Values of all columns merged into {@code target} come first so that the
     * target's own balances win on identical dates while monthly flows of the
     * merged columns are kept.
     */
    private static List<SnapshotValue> collectValues(OdsAccountColumn target, List<OdsAccountColumn> columns) {
        List<SnapshotValue> values = new ArrayList<>();
        for (OdsAccountColumn other : columns) {
            if (other.isInclude() && other.getMergeInto() == target) {
                values.addAll(other.values());
            }
        }
        values.addAll(target.values());
        return values;
    }

    private static Optional<OdsAccountColumn> mergeTarget(OdsAccountColumn column) {
        OdsAccountColumn target = column.getMergeInto();
        return target != null && target.isInclude() ? Optional.of(target) : Optional.empty();
    }

    private Account ensureInstitution(OdsAccountColumn column, Map<String, Account> institutions) {
        String key = normalize(column.getInstitutionName());
        Account institution = institutions.get(key);
        if (institution == null) {
            institution = new Account();
            institution.setName(column.getInstitutionName().trim());
            institution.setAccountType(AccountType.INSTITUTION);
            institution.setBankName(column.getBankName());
            institution = accountService.save(institution);
            institutions.put(key, institution);
        }
        return institution;
    }

    private Account saveAccount(OdsAccountColumn column, UUID parentId, int displayOrder) {
        Account account = column.getExistingAccountId() == null
                ? new Account()
                : accountService.findAll().stream()
                        .filter(candidate -> candidate.getId().equals(column.getExistingAccountId()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Account not found: " + column.getExistingAccountId()));
        AccountType type = column.getAccountType() == null ? AccountType.CHECKING : column.getAccountType();
        String name = blankToNull(column.getAccountName());
        if (name == null) {
            name = FinanceOdsParser.defaultTypeName(type);
        }
        String bank = blankToNull(column.getBankName());
        if (bank == null) {
            bank = blankToNull(column.getInstitutionName());
        }
        account.setName(name);
        account.setAccountType(type);
        account.setParentId(parentId);
        account.setAccountNumber(blankToNull(column.getAccountNumber()));
        account.setBankName(bank);
        account.setAccountHolder(blankToNull(column.getAccountHolder()));
        account.setActive(column.isActive());
        account.setDisplayOrder(displayOrder);
        return accountService.save(account);
    }

    private static Optional<Account> findExisting(OdsAccountColumn column, List<Account> accounts) {
        Optional<UUID> institutionId = accounts.stream()
                .filter(account -> account.getAccountType() == AccountType.INSTITUTION)
                .filter(account -> normalize(account.getName()).equals(normalize(column.getInstitutionName())))
                .map(Account::getId)
                .findFirst();
        String columnHolder = normalize(column.getAccountHolder());
        return accounts.stream()
                .filter(account -> account.getAccountType() != AccountType.INSTITUTION)
                .filter(account -> normalize(account.getName()).equals(normalize(column.getAccountName()))
                        || account.getAccountType() == column.getAccountType())
                .filter(account -> columnHolder.isEmpty() || normalize(account.getAccountHolder()).isEmpty()
                        || columnHolder.equals(normalize(account.getAccountHolder())))
                .filter(account -> !column.grouped()
                        || institutionId.map(id -> id.equals(account.getParentId())).orElse(false))
                .findFirst();
    }

    private static void linkExisting(OdsAccountColumn column, Account account) {
        column.setExistingAccountId(account.getId());
        column.setAccountType(account.getAccountType());
        column.setActive(account.isActive());
        if (account.getAccountNumber() != null) {
            column.setAccountNumber(account.getAccountNumber());
        }
        if (account.getBankName() != null) {
            column.setBankName(account.getBankName());
        }
        if (account.getAccountHolder() != null) {
            column.setAccountHolder(account.getAccountHolder());
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Outcome of a spreadsheet migration.
     */
    public record OdsMigrationResult(int institutions, int accountsCreated, int snapshotsRecorded) {
    }
}
