package de.in.mp.ui.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.UI;

import de.in.mp.admin.AppSettingService;

class SettingsTabTest {

    private AppSettingService appSettingService;
    private SettingsTab settingsTab;

    @BeforeEach
    void setUp() {
        UI.setCurrent(new UI());
        appSettingService = mock(AppSettingService.class);
        when(appSettingService.getSavingsInterestWarningThreshold()).thenReturn(new BigDecimal("2.00"));
        settingsTab = new SettingsTab(appSettingService);
    }

    @AfterEach
    void tearDown() {
        UI.setCurrent(null);
    }

    @Test
    void refreshesFieldFromService() {
        settingsTab.refresh();
        assertThat(settingsTab.savingsInterestThreshold.getValue()).isEqualByComparingTo("2.00");
    }

    @Test
    void savesValidThreshold() {
        settingsTab.savingsInterestThreshold.setValue(new BigDecimal("2.50"));
        settingsTab.save();

        verify(appSettingService).setSavingsInterestWarningThreshold(new BigDecimal("2.50"));
        assertThat(settingsTab.savingsInterestThreshold.isInvalid()).isFalse();
    }

    @Test
    void rejectsNegativeThreshold() {
        settingsTab.savingsInterestThreshold.setValue(new BigDecimal("-0.50"));
        settingsTab.save();

        verify(appSettingService, never()).setSavingsInterestWarningThreshold(any());
        assertThat(settingsTab.savingsInterestThreshold.isInvalid()).isTrue();
    }
}
