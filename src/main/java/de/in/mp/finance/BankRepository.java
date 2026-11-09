package de.in.mp.finance;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for cached bank master data.
 */
public interface BankRepository extends JpaRepository<Bank, UUID> {

    Optional<Bank> findByBlz(String blz);
}
