package de.in.mp.energy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain service for time-bounded energy tariffs and remuneration rates.
 * <p>
 * Automatically closes open predecessor tariffs when a new tariff period starts
 * and supports daily interpolation when a tariff changes mid-period.
 */
@Service
public class EnergyTariffService {

    private static final int NAME_MAX_LENGTH = 80;
    private static final int PRICE_SCALE = 4;

    private final EnergyTariffRepository repository;

    public EnergyTariffService(EnergyTariffRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<EnergyTariff> findAll() {
        return repository.findAllByOrderByForMeterTypeAscValidFromDesc();
    }

    @Transactional(readOnly = true)
    public List<EnergyTariff> findByMeterType(MeterType meterType) {
        return repository.findByForMeterTypeOrderByValidFromAsc(meterType);
    }

    @Transactional
    public EnergyTariff save(EnergyTariff tariff) {
        validate(tariff);
        closePredecessorTariffs(tariff);
        return repository.save(tariff);
    }

    @Transactional
    public void delete(UUID id) {
        EnergyTariff tariff = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Energy tariff not found: " + id));
        repository.delete(tariff);
    }

    @Transactional(readOnly = true)
    public Optional<BigDecimal> resolveUnitPrice(MeterType meterType, LocalDate date) {
        List<EnergyTariff> tariffs = repository.findByForMeterTypeOrderByValidFromAsc(meterType);
        return resolveUnitPriceFromList(tariffs, date);
    }

    /**
     * Calculates the effective unit price between {@code startExclusive} and
     * {@code endInclusive}, interpolating day-by-day if a tariff change occurred
     * within the interval.
     */
    @Transactional(readOnly = true)
    public BigDecimal resolveEffectiveUnitPrice(
            MeterType meterType, LocalDate startExclusive, LocalDate endInclusive) {
        List<EnergyTariff> tariffs = repository.findByForMeterTypeOrderByValidFromAsc(meterType);
        if (tariffs.isEmpty()) {
            return BigDecimal.ZERO;
        }
        if (startExclusive == null || endInclusive == null || !endInclusive.isAfter(startExclusive)) {
            LocalDate lookupDate = endInclusive != null ? endInclusive : LocalDate.now();
            return resolveUnitPriceFromList(tariffs, lookupDate).orElse(BigDecimal.ZERO);
        }

        long totalDays = ChronoUnit.DAYS.between(startExclusive, endInclusive);
        if (totalDays <= 0) {
            return resolveUnitPriceFromList(tariffs, endInclusive).orElse(BigDecimal.ZERO);
        }

        BigDecimal sumPrices = BigDecimal.ZERO;
        long matchedDays = 0;
        for (long i = 1; i <= totalDays; i++) {
            LocalDate day = startExclusive.plusDays(i);
            Optional<BigDecimal> dayPrice = resolveUnitPriceFromList(tariffs, day);
            if (dayPrice.isPresent()) {
                sumPrices = sumPrices.add(dayPrice.get());
                matchedDays++;
            }
        }

        if (matchedDays == 0) {
            // Fallback to nearest tariff if readings predate first configured tariff
            return tariffs.get(0).getUnitPriceEur();
        }
        return sumPrices.divide(BigDecimal.valueOf(matchedDays), PRICE_SCALE, RoundingMode.HALF_UP);
    }

    private Optional<BigDecimal> resolveUnitPriceFromList(List<EnergyTariff> tariffs, LocalDate date) {
        EnergyTariff match = null;
        for (EnergyTariff tariff : tariffs) {
            boolean afterStart = !date.isBefore(tariff.getValidFrom());
            boolean beforeEnd = tariff.getValidTo() == null || !date.isAfter(tariff.getValidTo());
            if (afterStart && beforeEnd) {
                match = tariff;
            }
        }
        return Optional.ofNullable(match).map(EnergyTariff::getUnitPriceEur);
    }

    private void closePredecessorTariffs(EnergyTariff newTariff) {
        List<EnergyTariff> existing = repository.findByForMeterTypeOrderByValidFromAsc(newTariff.getForMeterType());
        LocalDate dayBeforeNew = newTariff.getValidFrom().minusDays(1);

        for (EnergyTariff candidate : existing) {
            if (newTariff.getId() != null && newTariff.getId().equals(candidate.getId())) {
                continue;
            }
            if (candidate.getValidFrom().isBefore(newTariff.getValidFrom())
                    && (candidate.getValidTo() == null || !candidate.getValidTo().isBefore(newTariff.getValidFrom()))) {
                candidate.setValidTo(dayBeforeNew);
                repository.save(candidate);
            }
        }
    }

    private void validate(EnergyTariff tariff) {
        if (tariff == null) {
            throw new IllegalArgumentException("Energy tariff must not be null");
        }
        if (tariff.getName() == null || tariff.getName().isBlank()) {
            throw new IllegalArgumentException("Name is required");
        }
        String trimmedName = tariff.getName().trim();
        if (trimmedName.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("Name must not exceed " + NAME_MAX_LENGTH + " characters");
        }
        tariff.setName(trimmedName);

        if (tariff.getForMeterType() == null) {
            throw new IllegalArgumentException("Meter type is required");
        }
        if (tariff.getValidFrom() == null) {
            throw new IllegalArgumentException("Valid-from date is required");
        }
        if (tariff.getValidTo() != null && tariff.getValidTo().isBefore(tariff.getValidFrom())) {
            throw new IllegalArgumentException("Valid-to date must not be before valid-from date");
        }
        if (tariff.getUnitPriceEur() == null || tariff.getUnitPriceEur().signum() < 0) {
            throw new IllegalArgumentException("Unit price must not be negative");
        }
        if (tariff.getMonthlyBaseFeeEur() == null) {
            tariff.setMonthlyBaseFeeEur(BigDecimal.ZERO);
        }
        if (tariff.getMonthlyBaseFeeEur().signum() < 0) {
            throw new IllegalArgumentException("Monthly base fee must not be negative");
        }
    }
}

