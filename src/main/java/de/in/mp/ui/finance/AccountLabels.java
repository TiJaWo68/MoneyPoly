package de.in.mp.ui.finance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import de.in.mp.finance.Account;
import de.in.mp.finance.AccountType;

/**
 * Display helpers for accounts in the finance UI: institution-qualified
 * labels and the column order of the balance matrix.
 */
final class AccountLabels {

    private static final String SEPARATOR = " · ";

    private AccountLabels() {
    }

    static Map<UUID, Account> byId(List<Account> accounts) {
        return accounts.stream()
                .filter(account -> account.getId() != null)
                .collect(Collectors.toMap(Account::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }

    /**
     * Name of the grouping institution, or {@code null} for top-level accounts.
     */
    static String institutionName(Account account, Map<UUID, Account> byId) {
        Account parent = account.getParentId() == null ? null : byId.get(account.getParentId());
        return parent != null && parent.getAccountType() == AccountType.INSTITUTION ? parent.getName() : null;
    }

    static String qualifiedName(Account account, Map<UUID, Account> byId) {
        return qualifiedName(account, byId, type -> account.getName());
    }

    static String qualifiedName(Account account, Map<UUID, Account> byId,
            Function<AccountType, String> typeTranslator) {
        List<String> parts = new ArrayList<>();
        String institution = institutionName(account, byId);
        if (institution != null && !institution.isBlank()) {
            parts.add(institution.trim());
        }
        String typeLabel = account.getAccountType() == null
                ? account.getName()
                : typeTranslator.apply(account.getAccountType());
        if (typeLabel != null && !typeLabel.isBlank()) {
            parts.add(typeLabel.trim());
        }
        String holder = account.getAccountHolder();
        if (holder != null && !holder.isBlank()) {
            parts.add(holder.trim());
        }
        return String.join(SEPARATOR, parts);
    }

    /**
     * Orders balance-carrying accounts for the matrix: institutions keep the
     * position of their first account so that grouped columns stay adjacent;
     * accounts are sorted by display order, then by name.
     */
    static List<Account> matrixOrder(List<Account> accounts, Map<UUID, Account> byId) {
        Comparator<Account> byOrder = Comparator.comparingInt(Account::getDisplayOrder)
                .thenComparing(Account::getName, String.CASE_INSENSITIVE_ORDER);
        List<Account> sorted = accounts.stream()
                .filter(account -> account.getAccountType() != AccountType.INSTITUTION)
                .sorted(byOrder)
                .toList();

        Map<String, List<Account>> groups = new LinkedHashMap<>();
        for (Account account : sorted) {
            String institution = institutionName(account, byId);
            String key = institution == null ? "#" + account.getId() : institution;
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(account);
        }
        return groups.values().stream().flatMap(List::stream).toList();
    }
}
