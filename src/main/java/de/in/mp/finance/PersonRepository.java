package de.in.mp.finance;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for persons.
 */
public interface PersonRepository extends JpaRepository<Person, UUID> {

    List<Person> findAllByOrderByNameAsc();

    Optional<Person> findByName(String name);

    Optional<Person> findByLoginEmail(String loginEmail);
}
