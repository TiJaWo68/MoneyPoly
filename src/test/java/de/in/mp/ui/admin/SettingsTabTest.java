package de.in.mp.ui.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.UI;

import de.in.mp.admin.AppSettingService;
import de.in.mp.energy.vision.VisionConfig;
import de.in.mp.energy.vision.VisionMeterClient;
import de.in.mp.energy.vision.VisionPreset;

class SettingsTabTest {

    private UI ui;
    private AppSettingService appSettingService;
    private SettingsTab settingsTab;

    @BeforeEach
    void setUp() {
        ui = new UI();
        UI.setCurrent(ui);
        appSettingService = mock(AppSettingService.class);
        when(appSettingService.getSavingsInterestWarningThreshold()).thenReturn(new BigDecimal("2.00"));
        when(appSettingService.getVisionConfig()).thenReturn(VisionConfig.disabled());
        settingsTab = new SettingsTab(appSettingService);
    }

    @AfterEach
    void tearDown() {
        UI.setCurrent(null);
        ui = null;
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

    @Test
    void savesVisionConfigAlongWithThreshold() {
        settingsTab.savingsInterestThreshold.setValue(new BigDecimal("2.00"));
        settingsTab.visionPreset.setValue(VisionPreset.OLLAMA);
        settingsTab.visionBaseUrl.setValue("http://localhost:11434/v1");
        settingsTab.visionModel.setValue("qwen2.5vl:7b");
        settingsTab.save();

        verify(appSettingService).setVisionConfig(
                new VisionConfig(VisionPreset.OLLAMA, "http://localhost:11434/v1", "qwen2.5vl:7b", ""));
    }

    @Test
    void fetchesModelsIntoComboBoxAndPreservesSavedSelection() {
        VisionMeterClient client = mock(VisionMeterClient.class);
        when(client.listModels(any())).thenReturn(List.of("gemini-2.5-pro", "gemini-3-flash"));

        SettingsTab tab = new SettingsTab(appSettingService, client);
        tab.visionPreset.setValue(VisionPreset.GEMINI);
        tab.visionBaseUrl.setValue("https://generativelanguage.googleapis.com/v1beta/openai");
        tab.visionApiKey.setValue("secret");

        tab.fetchVisionModels();

        assertThat(tab.visionModel.getListDataView().getItems().toList())
                .containsExactly("gemini-2.5-pro", "gemini-3-flash");
        assertThat(tab.visionModel.getValue()).isEqualTo("gemini-2.5-pro");
    }

    @Test
    void testConnectionFlagsUnavailableModelWhilePopulatingLiveList() {
        VisionMeterClient client = mock(VisionMeterClient.class);
        when(client.listModels(any())).thenReturn(List.of("gemini-3-flash", "gemini-3-pro"));

        SettingsTab tab = new SettingsTab(appSettingService, client);
        tab.visionPreset.setValue(VisionPreset.GEMINI);
        tab.visionBaseUrl.setValue("https://generativelanguage.googleapis.com/v1beta/openai");
        tab.visionApiKey.setValue("secret");
        tab.setVisionModelOptions(List.of("gemini-1.5-flash"), "gemini-1.5-flash");

        tab.testVisionConnection();

        assertThat(tab.visionModel.getListDataView().getItems().toList())
                .contains("gemini-3-flash", "gemini-3-pro");
        assertThat(tab.visionModel.isInvalid()).isTrue();
    }
}
