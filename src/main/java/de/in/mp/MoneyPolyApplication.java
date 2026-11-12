package de.in.mp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Inline;
import com.vaadin.flow.server.AppShellSettings;
import com.vaadin.flow.server.LoadDependenciesOnStartup;
import com.vaadin.flow.theme.Theme;
import com.vaadin.flow.theme.lumo.Lumo;

import de.in.mp.ui.ResponsiveStyles;

/**
 * All web components are loaded eagerly: with route-based lazy chunks the
 * root route could render before its components (grid, progress bar) were
 * registered, leaving them invisible until another navigation.
 * <p>
 * Since Vaadin 25 the Lumo utility classes ({@code LumoUtility}) are no longer
 * part of the base theme and must be loaded explicitly.
 */
@SpringBootApplication
@Theme(variant = Lumo.DARK)
@StyleSheet(Lumo.UTILITY_STYLESHEET)
@StyleSheet(ResponsiveStyles.STYLESHEET)
@LoadDependenciesOnStartup
public class MoneyPolyApplication implements AppShellConfigurator {

	public static void main(String[] args) {
		SpringApplication.run(MoneyPolyApplication.class, args);
	}

	@Override
	public void configurePage(AppShellSettings settings) {
		settings.addFavIcon("icon", "/icons/moneypoly-logo.svg", "any");
		settings.addInlineWithContents(ResponsiveStyles.CSS_RULES, Inline.Wrapping.STYLESHEET);
	}
}