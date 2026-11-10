package de.in.mp.ui.energy;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import de.in.mp.energy.Meter;
import de.in.mp.energy.MeterReading;
import de.in.mp.energy.MeterType;

class EnergyViewTest {

    @Test
    void stripsGroupPrefixFromMeterShortHeader() {
        assertThat(EnergyView.shortColumnHeader(meter("Wasser Gesamt", MeterType.WATER))).isEqualTo("Gesamt");
        assertThat(EnergyView.shortColumnHeader(meter("Wasser HV", MeterType.WATER))).isEqualTo("HV");
        assertThat(EnergyView.shortColumnHeader(meter("Wasser HH", MeterType.WATER))).isEqualTo("HH");
        assertThat(EnergyView.shortColumnHeader(meter("Gas", MeterType.GAS))).isEqualTo("Gas");
        assertThat(EnergyView.shortColumnHeader(meter("Strom Gesamt", MeterType.GRID_IMPORT))).isEqualTo("Gesamt");
        assertThat(EnergyView.shortColumnHeader(meter("Strom Einspeisung", MeterType.GRID_EXPORT)))
                .isEqualTo("Einspeisung");
        assertThat(EnergyView.shortColumnHeader(meter("PV Gartenhaus", MeterType.PV_GENERATION)))
                .isEqualTo("Gartenhaus");
        assertThat(EnergyView.shortColumnHeader(meter("PV Dach", MeterType.PV_GENERATION))).isEqualTo("Dach");
        assertThat(EnergyView.shortColumnHeader(meter("Wallbox Laden", MeterType.WALLBOX_PRIVATE))).isEqualTo("Laden");
        assertThat(EnergyView.shortColumnHeader(meter("Wallbox weiß", MeterType.WALLBOX_COMPANY))).isEqualTo("weiß");
        assertThat(EnergyView.shortColumnHeader(meter("Wallbox ID4", MeterType.WALLBOX_COMPANY))).isEqualTo("ID4");
    }

    @Test
    void formatsMeterCellWithoutInlineDeltaAndBuildsTooltipWithAverageComparison() {
        MeterReading reading = new MeterReading();
        reading.setReadingValue(new BigDecimal("1593"));
        reading.setConsumptionDelta(new BigDecimal("18"));

        assertThat(EnergyView.formatMeterValueOnly(reading, Locale.GERMAN)).isEqualTo("1.593");

        String comparison = EnergyView.formatAverageComparison(
                new BigDecimal("18"), new BigDecimal("15"), Locale.GERMAN);
        assertThat(comparison).isEqualTo("+20% vs. Ø");

        String belowComparison = EnergyView.formatAverageComparison(
                new BigDecimal("12"), new BigDecimal("15"), Locale.GERMAN);
        assertThat(belowComparison).isEqualTo("-20% vs. Ø");
    }

    private static Meter meter(String name, MeterType type) {
        Meter meter = new Meter();
        meter.setName(name);
        meter.setType(type);
        return meter;
    }
}

