package de.in.mp.energy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EnergyTariffServiceTest {

        private EnergyTariffRepository repository;
        private EnergyTariffService service;

        @BeforeEach
        void setUp() {
                repository = mock(EnergyTariffRepository.class);
                service = new EnergyTariffService(repository);
        }

        @Test
        void saveAutomaticallyClosesPreviousOpenTariffOnDayBeforeNewStart() {
                EnergyTariff previous = tariff("Stromtarif 2025", MeterType.GRID_IMPORT,
                                LocalDate.of(2025, 1, 1), null, "0.3200");
                List<EnergyTariff> stored = new ArrayList<>(List.of(previous));

                when(repository.findByForMeterTypeOrderByValidFromAsc(MeterType.GRID_IMPORT)).thenReturn(stored);
                when(repository.save(any(EnergyTariff.class))).thenAnswer(inv -> inv.getArgument(0));

                EnergyTariff next = tariff("Stromtarif 2026", MeterType.GRID_IMPORT,
                                LocalDate.of(2026, 1, 1), null, "0.3400");

                service.save(next);

                assertThat(previous.getValidTo()).isEqualTo(LocalDate.of(2025, 12, 31));
                verify(repository).save(previous);
                verify(repository).save(next);
        }

        @Test
        void resolveUnitPriceReturnsMatchingTariffForDate() {
                EnergyTariff t2024 = tariff("Strom 2024", MeterType.GRID_IMPORT,
                                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), "0.3000");
                EnergyTariff t2025 = tariff("Strom 2025", MeterType.GRID_IMPORT,
                                LocalDate.of(2025, 1, 1), null, "0.3400");

                when(repository.findByForMeterTypeOrderByValidFromAsc(MeterType.GRID_IMPORT))
                                .thenReturn(List.of(t2024, t2025));

                assertThat(service.resolveUnitPrice(MeterType.GRID_IMPORT, LocalDate.of(2024, 7, 31)))
                                .contains(new BigDecimal("0.3000"));
                assertThat(service.resolveUnitPrice(MeterType.GRID_IMPORT, LocalDate.of(2025, 6, 30)))
                                .contains(new BigDecimal("0.3400"));
        }

        @Test
        void interpolateAverageUnitPriceSplitsAcrossMidPeriodTariffChange() {
                EnergyTariff firstHalf = tariff("Strom Alt", MeterType.GRID_IMPORT,
                                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 4, 15), "0.3000");
                EnergyTariff secondHalf = tariff("Strom Neu", MeterType.GRID_IMPORT,
                                LocalDate.of(2025, 4, 16), null, "0.4000");

                when(repository.findByForMeterTypeOrderByValidFromAsc(MeterType.GRID_IMPORT))
                                .thenReturn(List.of(firstHalf, secondHalf));

                // 30-day period (31.03.2025 -> 30.04.2025): 15 days (Apr 1..15) at 0.3000 and
                // 15 days (Apr 16..30) at 0.4000 => 0.3500
                BigDecimal price = service.resolveEffectiveUnitPrice(
                                MeterType.GRID_IMPORT, LocalDate.of(2025, 3, 31), LocalDate.of(2025, 4, 30));

                assertThat(price).isEqualByComparingTo("0.3500");
        }

        @Test
        void saveRejectsNegativeUnitPrice() {
                EnergyTariff invalid = tariff("Invalid", MeterType.GRID_IMPORT,
                                LocalDate.of(2025, 1, 1), null, "-0.0100");

                assertThatThrownBy(() -> service.save(invalid))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessageContaining("Unit price");
        }

        private EnergyTariff tariff(String name, MeterType type, LocalDate from, LocalDate to, String price) {
                EnergyTariff tariff = new EnergyTariff();
                tariff.setName(name);
                tariff.setForMeterType(type);
                tariff.setValidFrom(from);
                tariff.setValidTo(to);
                tariff.setUnitPriceEur(new BigDecimal(price));
                return tariff;
        }
}