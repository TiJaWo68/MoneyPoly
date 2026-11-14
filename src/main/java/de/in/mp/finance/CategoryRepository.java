package de.in.mp.finance;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence access for booking categories.
 */
public interface CategoryRepository extends JpaRepository<Category, UUID> {

    List<Category> findAllByOrderByNameAsc();

    List<Category> findByHouseholdIdOrderByNameAsc(UUID householdId);

    Optional<Category> findByName(String name);

    Optional<Category> findByHouseholdIdAndName(UUID householdId, String name);

    /**
     * Counts transaction allocations that reference the given category. The
     * allocation table is not mapped as a JPA entity yet, hence the native query.
     */
    @Query(value = "SELECT COUNT(*) FROM transaction_allocation WHERE category_id = :categoryId",
            nativeQuery = true)
    long countTransactionAllocations(@Param("categoryId") UUID categoryId);
}
