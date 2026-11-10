package de.in.mp.finance;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for finance accounts.
 */
public interface AccountRepository extends JpaRepository<Account, UUID> {

    /**
     * Active accounts ordered by account type and name.
     */
    List<Account> findByActiveTrueOrderByAccountTypeAscNameAsc();

    /**
     * All accounts ordered by account type and name (keeps deactivated accounts visible).
     */
    List<Account> findAllByOrderByAccountTypeAscNameAsc();

    /**
     * All accounts in balance-matrix column order.
     */
    List<Account> findAllByOrderByDisplayOrderAscNameAsc();

    long countByActiveTrue();
}
