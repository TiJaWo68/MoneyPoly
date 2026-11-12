package de.in.mp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.theme.lumo.Lumo;

import de.in.mp.ui.ResponsiveStyles;

class MoneyPolyApplicationStyleSheetTest {

    @Test
    void appShellLoadsLumoUtilityAndResponsiveStylesheets() {
        List<String> stylesheets = Arrays.stream(MoneyPolyApplication.class.getAnnotationsByType(StyleSheet.class))
                .map(StyleSheet::value)
                .toList();

        assertThat(stylesheets)
                .as("LumoUtility classes have no effect in Vaadin 25 unless the utility stylesheet is loaded")
                .contains(Lumo.UTILITY_STYLESHEET, ResponsiveStyles.STYLESHEET);
    }

    @Test
    void responsiveStylesheetIsPackagedAsStaticResource() {
        assertThat(getClass().getClassLoader().getResource("META-INF/resources/" + ResponsiveStyles.STYLESHEET))
                .as("Responsive stylesheet must be served from META-INF/resources")
                .isNotNull();
    }
}

