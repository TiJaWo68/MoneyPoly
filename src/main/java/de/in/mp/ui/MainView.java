package de.in.mp.ui;

import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;

/**
 * Platzhalter-Startseite. Wird in den späteren Milestones durch die Module
 * "Finance & Loans" und "Energy & ROI" ersetzt.
 */
@Route("")
public class MainView extends VerticalLayout {

    public MainView() {
        add(new H1("MoneyPoly"));
        add(new Paragraph("Family Finance, Loans & Energy Hub"));
    }
}
