package de.in.mp.importer;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public FinanceOdsImportService(AccountService accountService, AccountSnapshotService snapshotService,
            LoanContractService loanContractService) {
        this.accountService = accountService;
        this.snapshotService = snapshotService;
        this.loanContractService = loanContractService;
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
            if (loanDesignations.contains(normalize(column.subHeader()))) {
                column.setInclude(false);
            }
            findExisting(column, accounts).ifPresent(account -> linkExisting(column, account));
        }
        return columns;
    }

    @Transactional
    public OdsMigrationResult execute(List<OdsAccountColumn> columns) {
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
        account.setName(column.getAccountName().trim());
        account.setAccountType(column.getAccountType() == null ? AccountType.CHECKING : column.getAccountType());
        account.setParentId(parentId);
        account.setAccountNumber(blankToNull(column.getAccountNumber()));
        account.setBankName(blankToNull(column.getBankName()));
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
        return accounts.stream()
                .filter(account -> account.getAccountType() != AccountType.INSTITUTION)
                .filter(account -> normalize(account.getName()).equals(normalize(column.getAccountName())))
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
