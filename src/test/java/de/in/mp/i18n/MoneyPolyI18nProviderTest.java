package de.in.mp.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

class MoneyPolyI18nProviderTest {

	private final MoneyPolyI18nProvider provider = new MoneyPolyI18nProvider();

	@Test
	void providesGermanAndEnglishLocalesWithGermanAsDefault() {
		assertThat(provider.getProvidedLocales()).isEqualTo(List.of(Locale.GERMAN, Locale.ENGLISH));
	}

	@Test
	void resolvesGermanTranslationIncludingUmlauts() {
		assertThat(provider.getTranslation("ui.main.subtitle", Locale.GERMAN))
				.isEqualTo("Finanzen, Kredite & Energie-Hub");
	}

	@Test
	void resolvesEnglishTranslation() {
		assertThat(provider.getTranslation("ui.main.subtitle", Locale.ENGLISH))
				.isEqualTo("Finance, Loans & Energy Hub");
	}

	@Test
	void formatsGermanStatusMessageWithVersionParameter() {
		assertThat(provider.getTranslation("ui.main.status.ready", Locale.GERMAN, "0.0.1"))
				.isEqualTo("System betriebsbereit (v0.0.1)");
	}

	@Test
	void formatsEnglishStatusMessageWithVersionParameter() {
		assertThat(provider.getTranslation("ui.main.status.ready", Locale.ENGLISH, "0.0.1"))
				.isEqualTo("System operational (v0.0.1)");
	}

	@Test
	void fallsBackToGermanForUnsupportedLocale() {
		assertThat(provider.getTranslation("ui.main.subtitle", Locale.FRENCH))
				.isEqualTo("Finanzen, Kredite & Energie-Hub");
	}

	@Test
	void returnsKeyWhenTranslationIsMissing() {
		assertThat(provider.getTranslation("ui.main.unknown", Locale.GERMAN)).isEqualTo("ui.main.unknown");
	}

	@Test
	void keepsGermanAndEnglishBundlesInSync() {
		for (String key : List.of("ui.main.page-title", "ui.main.title", "ui.main.subtitle", "ui.main.status.ready",
				"ui.login.page-title", "ui.login.title", "ui.login.subtitle", "ui.login.form.username",
				"ui.login.form.password", "ui.login.form.submit", "ui.login.error.title", "ui.login.error.message",
				"ui.login.error.username", "ui.login.error.password", "ui.login.oauth2.google",
				"ui.setup.page-title", "ui.setup.title", "ui.setup.username.label", "ui.setup.email.label",
				"ui.setup.password.label", "ui.setup.password-confirmation.label", "ui.setup.token.label",
				"ui.setup.submit", "ui.setup.success.message", "ui.setup.error.username-required",
				"ui.setup.error.email-required", "ui.setup.error.email-invalid", "ui.setup.error.password-required",
				"ui.setup.error.password-min-length", "ui.setup.error.password-confirmation-required",
				"ui.setup.error.password-mismatch", "ui.setup.error.setup-token-required",
				"ui.setup.error.already-configured", "setup.error.invalid_token",
				"ui.app.title", "ui.nav.title", "ui.nav.dashboard", "ui.nav.finance", "ui.nav.loans",
				"ui.nav.energy", "ui.nav.admin", "ui.header.toggle", "ui.header.logged-in-as", "ui.header.logout",
				"energy.view.page-title", "energy.view.title", "energy.kpi.total-investment",
				"energy.kpi.total-amortized", "energy.kpi.remaining-cost", "energy.kpi.active-meters",
				"energy.tab.readings", "energy.tab.amortization", "energy.tab.masterdata",
				"energy.button.batch-entry", "energy.batch.title.new", "energy.batch.button.save",
				"ui.button.save", "ui.button.cancel", "ui.dialog.close",
				"dashboard.grid.column.interest", "dashboard.grid.column.performance",
				"finance.positions.dialog.title", "finance.positions.editor.title.new",
				"finance.positions.editor.title.edit", "finance.positions.button.add",
				"finance.positions.button.refresh", "finance.positions.button.import-pdf",
				"finance.positions.button.close", "finance.positions.notification.imported",
				"finance.positions.notification.import-error",
				"finance.balance.hint.position-managed",
				"finance.tab.exit-plan", "finance.action.toggle-exit-plan",
				"finance.exit-plan.section.parameters", "finance.exit-plan.button.calculate",
				"finance.exit-plan.kpi.available-capital", "finance.exit-plan.kpi.sustainable-budget",
				"finance.exit-plan.kpi.capital-at-pension", "finance.exit-plan.kpi.capital-at-target",
				"finance.exit-plan.kpi.exhausted-at", "finance.exit-plan.field.live-capital",
				"finance.exit-plan.field.live-capital.tooltip", "finance.exit-plan.field.reserve",
				"finance.exit-plan.field.reserve.tooltip", "finance.exit-plan.field.return-percent",
				"finance.exit-plan.field.return-percent.tooltip", "finance.exit-plan.field.mode",
				"finance.exit-plan.field.mode.tooltip", "finance.exit-plan.field.target-age",
				"finance.exit-plan.field.target-age.tooltip", "finance.exit-plan.field.residual-target",
				"finance.exit-plan.field.residual-target.tooltip", "finance.exit-plan.field.desired-annual-budget",
				"finance.exit-plan.field.desired-annual-budget.tooltip", "finance.exit-plan.field.include-secondary",
				"finance.exit-plan.field.include-secondary.tooltip",
				"finance.exit-plan.mode.CAPITAL_PRESERVATION",
				"finance.exit-plan.mode.DEPLETE_TO_AGE", "finance.exit-plan.mode.FIXED_WITHDRAWAL",
				"finance.exit-plan.person.primary.title", "finance.exit-plan.person.primary.default",
				"finance.exit-plan.person.secondary.title", "finance.exit-plan.person.secondary.default",
				"finance.exit-plan.person.name", "finance.exit-plan.person.name.tooltip",
				"finance.exit-plan.person.current-age", "finance.exit-plan.person.current-age.tooltip",
				"finance.exit-plan.person.exit-age", "finance.exit-plan.person.exit-age.tooltip",
				"finance.exit-plan.person.pension-age", "finance.exit-plan.person.pension-age.tooltip",
				"finance.exit-plan.person.monthly-pension", "finance.exit-plan.person.monthly-pension.tooltip",
				"finance.exit-plan.person.monthly-savings", "finance.exit-plan.person.monthly-savings.tooltip",
				"finance.exit-plan.person.monthly-bridge-kv", "finance.exit-plan.person.monthly-bridge-kv.tooltip",
				"finance.exit-plan.person.family-insured", "finance.exit-plan.person.family-insured.tooltip",
				"finance.exit-plan.grid.year", "finance.exit-plan.grid.age",
				"finance.exit-plan.grid.inflow", "finance.exit-plan.grid.kv",
				"finance.exit-plan.grid.budget", "finance.exit-plan.grid.withdrawal",
				"finance.exit-plan.grid.return", "finance.exit-plan.grid.end-capital",
				"finance.exit-plan.chart.empty", "finance.exit-plan.milestone.exit",
				"finance.exit-plan.milestone.pension", "finance.exit-plan.milestone.target")) {
			assertThat(provider.getTranslation(key, Locale.GERMAN))
					.as("German translation for %s", key)
					.isNotEqualTo(key);
			assertThat(provider.getTranslation(key, Locale.ENGLISH))
					.as("English translation for %s", key)
					.isNotEqualTo(key);
		}
	}
}
