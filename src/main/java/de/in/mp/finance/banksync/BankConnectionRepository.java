package de.in.mp.finance.banksync;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for {@link BankConnection} entities.
 */
public interface BankConnectionRepository extends JpaRepository<BankConnection, UUID> {

    List<BankConnection> findByHouseholdIdOrderByInstitutionNameAsc(UUID householdId);

    List<BankConnection> findByStatus(BankConnectionStatus status);
}
