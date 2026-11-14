package de.in.mp.finance;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link PortfolioPosition} entities.
 */
public interface PortfolioPositionRepository extends JpaRepository<PortfolioPosition, UUID> {

    List<PortfolioPosition> findByAccountIdOrderByNameAsc(UUID accountId);

    List<PortfolioPosition> findByAccountIdIn(Collection<UUID> accountIds);

    boolean existsByAccountId(UUID accountId);
}
