package de.in.mp.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AppSettingServiceTest {

    private AppSettingRepository repository;
    private AppSettingService service;

    @BeforeEach
    void setUp() {
        repository = mock(AppSettingRepository.class);
        service = new AppSettingService(repository);
    }

    @Test
    void returnsDefaultThresholdWhenSettingNotFound() {
        when(repository.findBySettingKey(AppSettingService.KEY_SAVINGS_INTEREST_WARNING_THRESHOLD))
                .thenReturn(Optional.empty());

        BigDecimal threshold = service.getSavingsInterestWarningThreshold();

        assertThat(threshold).isEqualByComparingTo(new BigDecimal("2.00"));
    }

    @Test
    void returnsStoredThresholdWhenPresent() {
        AppSetting setting = new AppSetting(AppSettingService.KEY_SAVINGS_INTEREST_WARNING_THRESHOLD, "3.50", "Desc");
        when(repository.findBySettingKey(AppSettingService.KEY_SAVINGS_INTEREST_WARNING_THRESHOLD))
                .thenReturn(Optional.of(setting));

        BigDecimal threshold = service.getSavingsInterestWarningThreshold();

        assertThat(threshold).isEqualByComparingTo(new BigDecimal("3.50"));
    }

    @Test
    void fallsBackToDefaultWhenStoredValueIsMalformed() {
        AppSetting setting = new AppSetting(AppSettingService.KEY_SAVINGS_INTEREST_WARNING_THRESHOLD, "invalid-num", "Desc");
        when(repository.findBySettingKey(AppSettingService.KEY_SAVINGS_INTEREST_WARNING_THRESHOLD))
                .thenReturn(Optional.of(setting));

        BigDecimal threshold = service.getSavingsInterestWarningThreshold();

        assertThat(threshold).isEqualByComparingTo(new BigDecimal("2.00"));
    }

    @Test
    void savesNewThresholdSetting() {
        when(repository.findBySettingKey(AppSettingService.KEY_SAVINGS_INTEREST_WARNING_THRESHOLD))
                .thenReturn(Optional.empty());

        service.setSavingsInterestWarningThreshold(new BigDecimal("2.75"));

        verify(repository).save(any(AppSetting.class));
    }

    @Test
    void updatesExistingThresholdSetting() {
        AppSetting existing = new AppSetting(AppSettingService.KEY_SAVINGS_INTEREST_WARNING_THRESHOLD, "2.00", "Desc");
        when(repository.findBySettingKey(AppSettingService.KEY_SAVINGS_INTEREST_WARNING_THRESHOLD))
                .thenReturn(Optional.of(existing));

        service.setSavingsInterestWarningThreshold(new BigDecimal("1.75"));

        assertThat(existing.getSettingValue()).isEqualTo("1.75");
        verify(repository).save(existing);
    }

    @Test
    void rejectsNullThreshold() {
        assertThatThrownBy(() -> service.setSavingsInterestWarningThreshold(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeThreshold() {
        assertThatThrownBy(() -> service.setSavingsInterestWarningThreshold(new BigDecimal("-0.01")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getSettingAndSaveSetting() {
        when(repository.findBySettingKey("custom.key")).thenReturn(Optional.of(new AppSetting("custom.key", "foo", "bar")));

        Optional<String> val = service.getSetting("custom.key");

        assertThat(val).contains("foo");

        service.saveSetting("custom.key", "baz", "new desc");
        verify(repository).save(any(AppSetting.class));
    }
}
