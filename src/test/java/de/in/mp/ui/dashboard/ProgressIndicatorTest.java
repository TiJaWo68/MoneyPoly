package de.in.mp.ui.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class ProgressIndicatorTest {

    @Test
    void showsBarValueAndPercentLabel() {
        ProgressIndicator indicator = new ProgressIndicator(new BigDecimal("42.5"));

        assertThat(indicator.getBar().getValue()).isEqualTo(0.425);
        assertThat(indicator.getLabel().getText()).isEqualTo("42.5%");
    }

    @Test
    void clampsBarValueButKeepsExactLabel() {
        ProgressIndicator indicator = new ProgressIndicator(new BigDecimal("130.0"));

        assertThat(indicator.getBar().getValue()).isEqualTo(1.0);
        assertThat(indicator.getLabel().getText()).isEqualTo("130.0%");
    }

    @Test
    void treatsNullAsZero() {
        ProgressIndicator indicator = new ProgressIndicator(null);

        assertThat(indicator.getBar().getValue()).isZero();
        assertThat(indicator.getLabel().getText()).isEqualTo("0%");
    }

    @Test
    void barAndLabelNeverShrinkInFlexContainers() {
        ProgressIndicator indicator = new ProgressIndicator(BigDecimal.TEN);

        assertThat(indicator.getBar().getStyle().get("flex-shrink")).isEqualTo("0");
        assertThat(indicator.getBar().getMinWidth()).isEqualTo(ProgressIndicator.BAR_WIDTH);
        assertThat(indicator.getLabel().getStyle().get("flex-shrink")).isEqualTo("0");
        assertThat(indicator.getLabel().getMinWidth()).isEqualTo(ProgressIndicator.LABEL_WIDTH);
    }
}
