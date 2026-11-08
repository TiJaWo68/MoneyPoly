package de.in.mp.ui;

import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.theme.lumo.LumoUtility;
import org.springframework.beans.factory.annotation.Value;

import de.in.mp.ui.layout.MainLayout;
import jakarta.annotation.security.PermitAll;

@Route(value = "", layout = MainLayout.class)
@PermitAll
public class MainView extends VerticalLayout implements HasDynamicTitle {

	public MainView(@Value("${moneypoly.version:unknown}") String version) {
		setSizeFull();
		setJustifyContentMode(JustifyContentMode.CENTER);
		setAlignItems(Alignment.CENTER);

		// Container card
		VerticalLayout card = new VerticalLayout();
		card.setMaxWidth("450px");
		card.setPadding(true);
		card.setAlignItems(Alignment.CENTER);
		card.addClassNames(LumoUtility.Background.CONTRAST_5, LumoUtility.BorderRadius.LARGE, LumoUtility.BoxShadow.MEDIUM, LumoUtility.Padding.XLARGE);

		H1 title = new H1(getTranslation("ui.main.title"));
		title.addClassNames(LumoUtility.Margin.Bottom.XSMALL);

		Paragraph subtitle = new Paragraph(getTranslation("ui.main.subtitle"));
		subtitle.addClassNames(LumoUtility.TextColor.SECONDARY, LumoUtility.Margin.Top.NONE);

		// Status badge
		Span status = new Span(VaadinIcon.CHECK_CIRCLE.create(), new Span(getTranslation("ui.main.status.ready", version)));
		status.getElement().getThemeList().add("badge success pill");

		card.add(title, subtitle, status);
		add(card);
	}

	@Override
	public String getPageTitle() {
		return getTranslation("ui.main.page-title");
	}
}
