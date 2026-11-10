package de.in.mp.finance;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for account balance snapshots.
 */
public interface AccountSnapshotRepository extends JpaRepository<AccountSnapshot, UUID> {

    Optional<AccountSnapshot> findByAccountIdAndSnapshotDate(UUID accountId, LocalDate snapshotDate);

    Optional<AccountSnapshot> findFirstByAccountIdOrderBySnapshotDateDesc(UUID accountId);

    List<AccountSnapshot> findAllByOrderBySnapshotDateDesc();

    List<AccountSnapshot> findBySnapshotDate(LocalDate snapshotDate);
}
