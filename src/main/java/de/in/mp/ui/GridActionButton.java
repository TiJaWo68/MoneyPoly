package de.in.mp.ui;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.icon.VaadinIcon;

/**
 * Factory for compact icon-only action buttons rendered inside table/grid rows.
 * <p>
 * Every button carries a tooltip and an {@code aria-label} so users receive a
 * clear hint on hover and assistive technologies can announce the action.
 */
public final class GridActionButton {

    private GridActionButton() {
    }

    /**
     * Creates a standard compact icon action button with tooltip.
     */
    public static Button create(
            VaadinIcon icon,
            String tooltipText,
            ComponentEventListener<ClickEvent<Button>> clickListener) {
        Button button = new Button(icon.create(), clickListener);
        button.setTooltipText(tooltipText);
        button.setAriaLabel(tooltipText);
        button.addThemeVariants(
                ButtonVariant.LUMO_ICON,
                ButtonVariant.LUMO_TERTIARY_INLINE,
                ButtonVariant.LUMO_SMALL);
        return button;
    }

    /**
     * Creates a compact destructive/danger icon action button with tooltip.
     */
    public static Button createDanger(
            VaadinIcon icon,
            String tooltipText,
            ComponentEventListener<ClickEvent<Button>> clickListener) {
        Button button = create(icon, tooltipText, clickListener);
        button.addThemeVariants(ButtonVariant.LUMO_ERROR);
        return button;
    }
}

