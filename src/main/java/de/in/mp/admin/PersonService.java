package de.in.mp.admin;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.in.mp.finance.Person;
import de.in.mp.finance.PersonRepository;
import de.in.mp.loans.LoanContractRepository;

/**
 * Administration service for persons participating in household finances and
 * private loans.
 * <p>
 * Persons with active loan contracts cannot be deleted (delete protection).
 */
@Service
public class PersonService {

    private static final int NAME_MAX_LENGTH = 50;
    private static final int EMAIL_MAX_LENGTH = 120;

    private final PersonRepository personRepository;
    private final LoanContractRepository loanContractRepository;

    public PersonService(PersonRepository personRepository, LoanContractRepository loanContractRepository) {
        this.personRepository = personRepository;
        this.loanContractRepository = loanContractRepository;
    }

    @Transactional(readOnly = true)
    public List<Person> findAll() {
        return personRepository.findAllByOrderByNameAsc();
    }

    @Transactional
    public Person save(Person person) {
        validate(person);
        return personRepository.save(person);
    }

    @Transactional
    public void delete(UUID id) {
        Person person = requirePerson(id);
        if (loanContractRepository.countByBorrowerIdAndActiveTrue(id) > 0) {
            throw new IllegalArgumentException("Person cannot be deleted while active loan contracts exist");
        }
        personRepository.delete(person);
    }

    @Transactional(readOnly = true)
    public long countActiveLoans(UUID personId) {
        return loanContractRepository.countByBorrowerIdAndActiveTrue(personId);
    }

    private void validate(Person person) {
        if (person == null) {
            throw new IllegalArgumentException("Person must not be null");
        }
        if (person.getName() == null || person.getName().isBlank()) {
            throw new IllegalArgumentException("Name is required");
        }
        String name = person.getName().trim();
        if (name.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("Name must not exceed " + NAME_MAX_LENGTH + " characters");
        }
        person.setName(name);

        if (person.getRole() == null) {
            throw new IllegalArgumentException("Role is required");
        }
        validateLoginEmail(person);

        personRepository.findByName(name).ifPresent(existing -> {
            if (!existing.getId().equals(person.getId())) {
                throw new IllegalArgumentException("Name already exists");
            }
        });
    }

    private void validateLoginEmail(Person person) {
        if (person.getLoginEmail() == null || person.getLoginEmail().isBlank()) {
            person.setLoginEmail(null);
            return;
        }
        String loginEmail = person.getLoginEmail().trim();
        if (loginEmail.length() > EMAIL_MAX_LENGTH) {
            throw new IllegalArgumentException("Login email must not exceed " + EMAIL_MAX_LENGTH + " characters");
        }
        person.setLoginEmail(loginEmail);
        personRepository.findByLoginEmail(loginEmail).ifPresent(existing -> {
            if (!existing.getId().equals(person.getId())) {
                throw new IllegalArgumentException("Login email already exists");
            }
        });
    }

    private Person requirePerson(UUID id) {
        return personRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Person not found: " + id));
    }
}
