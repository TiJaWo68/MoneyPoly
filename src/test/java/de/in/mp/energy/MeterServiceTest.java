package de.in.mp.energy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MeterServiceTest {

    private MeterRepository repository;
    private MeterService service;

    @BeforeEach
    void setUp() {
        repository = mock(MeterRepository.class);
        service = new MeterService(repository);
    }

    @Test
    void saveRejectsBlankName() {
        Meter meter = meter("   ", MeterType.GRID_IMPORT);

        assertThatThrownBy(() -> service.save(meter))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Name");
    }

    @Test
    void saveRejectsNullType() {
        Meter meter = meter("Strom Gesamt", null);

        assertThatThrownBy(() -> service.save(meter))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Meter type");
    }

    @Test
    void saveRejectsNonPositiveConversionFactor() {
        Meter meter = meter("Gas", MeterType.GAS);
        meter.setConversionFactor(BigDecimal.ZERO);

        assertThatThrownBy(() -> service.save(meter))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Conversion factor");
    }

    @Test
    void saveRejectsNonPositiveOverflowThreshold() {
        Meter meter = meter("Strom HH", MeterType.GRID_IMPORT);
        meter.setOverflowThreshold(new BigDecimal("-100"));

        assertThatThrownBy(() -> service.save(meter))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Overflow threshold");
    }

    @Test
    void saveDefaultsUnitBasedOnMeterTypeWhenBlank() {
        Meter waterMeter = meter("Wasser Gesamt", MeterType.WATER);
        waterMeter.setUnit(" ");
        when(repository.save(any(Meter.class))).thenAnswer(inv -> inv.getArgument(0));

        Meter saved = service.save(waterMeter);

        assertThat(saved.getUnit()).isEqualTo("m3");
    }

    @Test
    void findActiveReturnsActiveMetersOrdered() {
        Meter meter = meter("Strom Gesamt", MeterType.GRID_IMPORT);
        when(repository.findByActiveTrueOrderByDisplayOrderAscNameAsc()).thenReturn(List.of(meter));

        assertThat(service.findActive()).containsExactly(meter);
    }

    private Meter meter(String name, MeterType type) {
        Meter meter = new Meter();
        meter.setName(name);
        meter.setType(type);
        meter.setUnit("kWh");
        return meter;
    }
}

