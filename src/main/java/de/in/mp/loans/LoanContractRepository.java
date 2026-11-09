package de.in.mp.loans;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for family loan contracts.
 */
public interface LoanContractRepository extends JpaRepository<LoanContract, UUID> {

    List<LoanContract> findAllByOrderByDesignationAsc();

    List<LoanContract> findByActiveTrueOrderByDesignationAsc();

    long countByBorrowerIdAndActiveTrue(UUID borrowerId);

    long countByBorrowerId(UUID borrowerId);
}
