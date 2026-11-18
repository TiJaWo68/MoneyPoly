package de.in.mp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WealthEventServiceTest {

    private WealthEventRepository repository;
    private HouseholdService householdService;
    private WealthEventService service;

    @BeforeEach
    void setUp() {
        repository = mock(WealthEventRepository.class);
        householdService = mock(HouseholdService.class);
        service = new WealthEventService(repository, householdService);
    }

    @Test
    void findByHouseholdUsesEffectiveHouseholdId() {
        UUID requested = UUID.randomUUID();
        UUID effective = UUID.randomUUID();
        WealthEvent ev = event(effective, LocalDate.of(2025, 3, 15), "-5000.00", "Car repair");

        when(householdService.resolveEffectiveHouseholdId(requested)).thenReturn(effective);
        when(repository.findByHouseholdIdOrderByEventDateAscCreatedAtAsc(effective)).thenReturn(List.of(ev));

        assertThat(service.findByHousehold(requested)).containsExactly(ev);
    }

    @Test
    void findAllFallsBackToRepositoryAllWhenHouseholdIsGlobal() {
        WealthEvent ev = event(UUID.randomUUID(), LocalDate.of(2025, 4, 1), "-1200.00", "Appliance");
        when(householdService.resolveEffectiveHouseholdId(null)).thenReturn(null);
        when(repository.findAllByOrderByEventDateAscCreatedAtAsc()).thenReturn(List.of(ev));

        assertThat(service.findAll()).containsExactly(ev);
    }

    @Test
    void saveResolvesHouseholdNormalizesScaleAndTrimsDescription() {
        UUID effectiveHousehold = UUID.randomUUID();
        when(householdService.resolveEffectiveHouseholdId(null)).thenReturn(effectiveHousehold);
        when(repository.save(any(WealthEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        WealthEvent input = new WealthEvent();
        input.setEventDate(LocalDate.of(2025, 5, 10));
        input.setAmount(new BigDecimal("-4999.996"));
        input.setDescription("   Kitchen renovation   ");

        WealthEvent saved = service.save(input);

        assertThat(saved.getHouseholdId()).isEqualTo(effectiveHousehold);
        assertThat(saved.getAmount()).isEqualByComparingTo("-5000.00");
        assertThat(saved.getDescription()).isEqualTo("Kitchen renovation");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void saveRejectsMissingOrInvalidFields() {
        UUID householdId = UUID.randomUUID();

        WealthEvent zeroAmount = event(householdId, LocalDate.of(2025, 1, 1), "0", "Zero");
        assertThatThrownBy(() -> service.save(zeroAmount))
                .isInstanceOf(IllegalArgumentException.class);

        WealthEvent blankDesc = event(householdId, LocalDate.of(2025, 1, 1), "-100", "   ");
        assertThatThrownBy(() -> service.save(blankDesc))
                .isInstanceOf(IllegalArgumentException.class);

        WealthEvent missingDate = event(householdId, null, "-100", "Missing date");
        assertThatThrownBy(() -> service.save(missingDate))
                .isInstanceOf(IllegalArgumentException.class);

        WealthEvent tooLong = event(householdId, LocalDate.of(2025, 1, 1), "-100", "x".repeat(256));
        assertThatThrownBy(() -> service.save(tooLong))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deleteRemovesExistingOrThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        WealthEvent existing = event(UUID.randomUUID(), LocalDate.of(2025, 2, 1), "-3000", "Roof repair");
        existing.setId(id);

        when(repository.findById(id)).thenReturn(Optional.of(existing));
        service.delete(id);
        verify(repository).delete(existing);

        UUID unknownId = UUID.randomUUID();
        when(repository.findById(unknownId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(unknownId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static WealthEvent event(UUID householdId, LocalDate date, String amount, String desc) {
        WealthEvent event = new WealthEvent();
        event.setHouseholdId(householdId);
        event.setEventDate(date);
        event.setAmount(new BigDecimal(amount));
        event.setDescription(desc);
        return event;
    }
}
