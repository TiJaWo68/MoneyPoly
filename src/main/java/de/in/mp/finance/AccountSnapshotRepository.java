package de.in.mp.finance;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence access for account balance snapshots.
 */
public interface AccountSnapshotRepository extends JpaRepository<AccountSnapshot, UUID> {

    Optional<AccountSnapshot> findByAccountIdAndSnapshotDate(UUID accountId, LocalDate snapshotDate);

    Optional<AccountSnapshot> findFirstByAccountIdOrderBySnapshotDateDesc(UUID accountId);

    List<AccountSnapshot> findAllByOrderBySnapshotDateDesc();

    List<AccountSnapshot> findBySnapshotDate(LocalDate snapshotDate);

    List<AccountSnapshot> findByAccountIdAndSnapshotDateBetweenOrderBySnapshotDateDesc(
            UUID accountId, LocalDate startDate, LocalDate endDate);

    @Query("""
            SELECT s FROM AccountSnapshot s
            WHERE s.accountId IN :accountIds
              AND s.snapshotDate = (
                  SELECT MAX(s2.snapshotDate) FROM AccountSnapshot s2 WHERE s2.accountId = s.accountId
              )
            """)
    List<AccountSnapshot> findLatestByAccountIds(@Param("accountIds") Collection<UUID> accountIds);
}
