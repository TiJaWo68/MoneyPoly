package de.in.mp.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.in.mp.finance.Person;
import de.in.mp.finance.PersonRepository;
import de.in.mp.finance.PersonRole;
import de.in.mp.loans.LoanContractRepository;

class PersonServiceTest {

    private PersonRepository personRepository;
    private LoanContractRepository loanContractRepository;
    private PersonService service;

    @BeforeEach
    void setUp() {
        personRepository = mock(PersonRepository.class);
        loanContractRepository = mock(LoanContractRepository.class);
        service = new PersonService(personRepository, loanContractRepository);
    }

    @Test
    void saveRejectsBlankName() {
        Person person = person(null, "   ", PersonRole.BORROWER);

        assertThatThrownBy(() -> service.save(person))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Name");
    }

    @Test
    void saveRejectsDuplicateName() {
        Person existing = person(UUID.randomUUID(), "Anna", PersonRole.LENDER);
        when(personRepository.findByName("Anna")).thenReturn(Optional.of(existing));

        Person person = person(null, "Anna", PersonRole.BORROWER);

        assertThatThrownBy(() -> service.save(person))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void deleteRejectsPersonWithActiveLoans() {
        UUID id = UUID.randomUUID();
        Person person = person(id, "Anna", PersonRole.BORROWER);
        when(personRepository.findById(id)).thenReturn(Optional.of(person));
        when(loanContractRepository.countByDebtorIdAndActiveTrue(id)).thenReturn(1L);

        assertThatThrownBy(() -> service.delete(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active loan");

        verify(personRepository, never()).delete(any(Person.class));
    }

    @Test
    void deleteSucceedsWithoutActiveLoans() {
        UUID id = UUID.randomUUID();
        Person person = person(id, "Anna", PersonRole.BORROWER);
        when(personRepository.findById(id)).thenReturn(Optional.of(person));
        when(loanContractRepository.countByDebtorIdAndActiveTrue(id)).thenReturn(0L);

        service.delete(id);

        verify(personRepository).delete(person);
    }

    @Test
    void countActiveLoansDelegatesToRepository() {
        UUID personId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(loanContractRepository.countByDebtorIdAndActiveTrue(personId)).thenReturn(3L);

        assertThat(service.countActiveLoans(personId)).isEqualTo(3L);
    }

    private Person person(UUID id, String name, PersonRole role) {
        Person person = new Person();
        person.setId(id);
        person.setName(name);
        person.setRole(role);
        return person;
    }
}
