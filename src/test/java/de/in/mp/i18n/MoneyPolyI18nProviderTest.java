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
				"ui.login.page-title", "ui.login.title", "ui.login.form.title", "ui.login.form.username",
				"ui.login.form.password", "ui.login.form.submit", "ui.login.error.title", "ui.login.error.message",
				"ui.login.error.username", "ui.login.error.password", "ui.login.oauth2.google",
				"ui.app.title", "ui.nav.title", "ui.nav.dashboard", "ui.nav.finance", "ui.nav.loans",
				"ui.nav.energy", "ui.nav.admin", "ui.header.toggle", "ui.header.logged-in-as", "ui.header.logout")) {
			assertThat(provider.getTranslation(key, Locale.GERMAN))
					.as("German translation for %s", key)
					.isNotEqualTo(key);
			assertThat(provider.getTranslation(key, Locale.ENGLISH))
					.as("English translation for %s", key)
					.isNotEqualTo(key);
		}
	}
}
