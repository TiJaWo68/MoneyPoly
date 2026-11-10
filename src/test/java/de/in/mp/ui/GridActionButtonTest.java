package de.in.mp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.icon.VaadinIcon;

class GridActionButtonTest {

    @Test
    void createsIconOnlyActionButtonWithTooltipAndAriaLabel() {
        AtomicBoolean clicked = new AtomicBoolean(false);

        Button button = GridActionButton.create(
                VaadinIcon.EDIT,
                "Bearbeiten",
                event -> clicked.set(true));

        assertThat(button.getText()).isEmpty();
        assertThat(button.getIcon()).isNotNull();
        assertThat(button.getTooltip().getText()).isEqualTo("Bearbeiten");
        assertThat(button.getAriaLabel()).hasValue("Bearbeiten");
        assertThat(button.getThemeNames())
                .contains(ButtonVariant.LUMO_ICON.getVariantName(),
                        ButtonVariant.LUMO_TERTIARY_INLINE.getVariantName(),
                        ButtonVariant.LUMO_SMALL.getVariantName());

        button.click();
        assertThat(clicked).isTrue();
    }

    @Test
    void createsDangerActionButtonWithErrorThemeVariant() {
        Button button = GridActionButton.createDanger(
                VaadinIcon.TRASH,
                "Löschen",
                event -> {
                });

        assertThat(button.getText()).isEmpty();
        assertThat(button.getTooltip().getText()).isEqualTo("Löschen");
        assertThat(button.getThemeNames())
                .contains(ButtonVariant.LUMO_ICON.getVariantName(),
                        ButtonVariant.LUMO_TERTIARY_INLINE.getVariantName(),
                        ButtonVariant.LUMO_SMALL.getVariantName(),
                        ButtonVariant.LUMO_ERROR.getVariantName());
    }
}

