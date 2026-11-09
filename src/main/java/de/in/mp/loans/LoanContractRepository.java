package de.in.mp.loans;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence access for private loan contracts.
 */
public interface LoanContractRepository extends JpaRepository<LoanContract, UUID> {

    List<LoanContract> findAllByOrderByDesignationAsc();

    List<LoanContract> findByActiveTrueOrderByDesignationAsc();

    /**
     * Counts active contracts where the given person is a debtor.
     */
    @Query("select count(c) from LoanContract c join c.debtorIds d where d = :personId and c.active = true")
    long countByDebtorIdAndActiveTrue(@Param("personId") UUID personId);
}
