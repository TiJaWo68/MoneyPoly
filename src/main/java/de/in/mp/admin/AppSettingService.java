package de.in.mp.admin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for accessing and updating global application configuration settings.
 */
@Service
public class AppSettingService {

    public static final String KEY_SAVINGS_INTEREST_WARNING_THRESHOLD = "savings_interest_warning_threshold";
    public static final BigDecimal DEFAULT_SAVINGS_INTEREST_WARNING_THRESHOLD = new BigDecimal("2.00");

    private final AppSettingRepository settingRepository;

    public AppSettingService(AppSettingRepository settingRepository) {
        this.settingRepository = settingRepository;
    }

    @Transactional(readOnly = true)
    public BigDecimal getSavingsInterestWarningThreshold() {
        return settingRepository.findBySettingKey(KEY_SAVINGS_INTEREST_WARNING_THRESHOLD)
                .map(AppSetting::getSettingValue)
                .map(val -> {
                    try {
                        return new BigDecimal(val.trim());
                    } catch (NumberFormatException exception) {
                        return DEFAULT_SAVINGS_INTEREST_WARNING_THRESHOLD;
                    }
                })
                .orElse(DEFAULT_SAVINGS_INTEREST_WARNING_THRESHOLD);
    }

    @Transactional
    public void setSavingsInterestWarningThreshold(BigDecimal threshold) {
        if (threshold == null || threshold.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Warning threshold must be a non-negative number");
        }
        BigDecimal normalized = threshold.setScale(2, RoundingMode.HALF_UP);
        saveSetting(KEY_SAVINGS_INTEREST_WARNING_THRESHOLD, normalized.toPlainString(),
                "Warnschwelle für Tagesgeld-Zinsunterschreitung in Prozent");
    }

    @Transactional(readOnly = true)
    public Optional<String> getSetting(String key) {
        return settingRepository.findBySettingKey(key).map(AppSetting::getSettingValue);
    }

    @Transactional
    public void saveSetting(String key, String value, String description) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Setting key must not be blank");
        }
        if (value == null) {
            throw new IllegalArgumentException("Setting value must not be null");
        }
        AppSetting setting = settingRepository.findBySettingKey(key)
                .orElseGet(() -> new AppSetting(key, value, description));
        setting.setSettingValue(value);
        if (description != null) {
            setting.setDescription(description);
        }
        settingRepository.save(setting);
    }
}
