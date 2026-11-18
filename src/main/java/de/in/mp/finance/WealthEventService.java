package de.in.mp.finance;

import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages {@link WealthEvent} entries (major expenses and private loan links)
 * scoped by household for annotating the net worth history chart.
 */
@Service
public class WealthEventService {

    private static final int DESCRIPTION_MAX_LENGTH = 255;

    private final WealthEventRepository repository;
    private final HouseholdService householdService;

    public WealthEventService(WealthEventRepository repository) {
        this(repository, null);
    }

    @Autowired
    public WealthEventService(
            WealthEventRepository repository,
            @Autowired(required = false) HouseholdService householdService) {
        this.repository = repository;
        this.householdService = householdService;
    }

    @Transactional(readOnly = true)
    public List<WealthEvent> findAll() {
        UUID householdId = householdService == null ? null : householdService.resolveEffectiveHouseholdId(null);
        return householdId == null
                ? repository.findAllByOrderByEventDateAscCreatedAtAsc()
                : repository.findByHouseholdIdOrderByEventDateAscCreatedAtAsc(householdId);
    }

    @Transactional(readOnly = true)
    public List<WealthEvent> findByHousehold(UUID householdId) {
        UUID effectiveId = householdService == null
                ? householdId
                : householdService.resolveEffectiveHouseholdId(householdId);
        return effectiveId == null
                ? repository.findAllByOrderByEventDateAscCreatedAtAsc()
                : repository.findByHouseholdIdOrderByEventDateAscCreatedAtAsc(effectiveId);
    }

    @Transactional
    public WealthEvent save(WealthEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("Wealth event must not be null");
        }
        if (event.getHouseholdId() == null && householdService != null) {
            event.setHouseholdId(householdService.resolveEffectiveHouseholdId(null));
        }
        if (event.getHouseholdId() == null) {
            throw new IllegalArgumentException("Household ID is required");
        }
        if (event.getEventDate() == null) {
            throw new IllegalArgumentException("Event date is required");
        }
        if (event.getAmount() == null || event.getAmount().signum() == 0) {
            throw new IllegalArgumentException("Amount must not be zero");
        }
        if (event.getDescription() == null || event.getDescription().isBlank()) {
            throw new IllegalArgumentException("Description is required");
        }
        String trimmed = event.getDescription().trim();
        if (trimmed.length() > DESCRIPTION_MAX_LENGTH) {
            throw new IllegalArgumentException("Description must not exceed " + DESCRIPTION_MAX_LENGTH + " characters");
        }
        event.setDescription(trimmed);
        event.setAmount(event.getAmount().setScale(2, RoundingMode.HALF_UP));
        if (event.getCreatedAt() == null) {
            event.setCreatedAt(Instant.now());
        }
        return repository.save(event);
    }

    @Transactional
    public void delete(UUID id) {
        WealthEvent existing = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Wealth event not found: " + id));
        repository.delete(existing);
    }
}
