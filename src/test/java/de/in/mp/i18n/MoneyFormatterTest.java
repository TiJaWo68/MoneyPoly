package de.in.mp.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Locale;

import org.junit.jupiter.api.Test;

class MoneyFormatterTest {

    @Test
    void formatsGermanLocaleWithEuroSymbolAndGermanGrouping() {
        String result = MoneyFormatter.format(new BigDecimal("42549.00"), Locale.GERMAN);

        assertThat(result).doesNotContain("¤");
        assertThat(result).contains("€");
        assertThat(result).contains("42.549,00");
    }

    @Test
    void formatsEnglishLocaleWithEuroSymbolAndEnglishGrouping() {
        String result = MoneyFormatter.format(new BigDecimal("42549.00"), Locale.ENGLISH);

        assertThat(result).doesNotContain("¤");
        assertThat(result).contains("€");
        assertThat(result).contains("42,549.00");
    }

    @Test
    void fallsBackToGermanLocaleForNull() {
        String result = MoneyFormatter.format(new BigDecimal("1000.00"), null);

        assertThat(result).doesNotContain("¤");
        assertThat(result).contains("€");
        assertThat(result).contains("1.000,00");
    }
}
