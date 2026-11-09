package de.in.mp.ui.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;

/**
 * Fixed-size progress visualization (bar plus right-aligned percent label)
 * shared by grid rows and section headers so that shares and repayment
 * progress line up identically. Children never shrink, otherwise the bar
 * collapses to zero width inside narrow flex containers.
 */
public class ProgressIndicator extends HorizontalLayout {

    static final String BAR_WIDTH = "90px";
    static final String LABEL_WIDTH = "45px";
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final String FLEX_SHRINK = "flex-shrink";
    private static final String NO_SHRINK = "0";

    private final ProgressBar bar = new ProgressBar(0, 1, 0);
    private final Span label = new Span();

    public ProgressIndicator() {
        bar.setWidth(BAR_WIDTH);
        bar.setMinWidth(BAR_WIDTH);
        bar.getStyle().set(FLEX_SHRINK, NO_SHRINK);
        bar.getStyle().set("margin", "0");

        label.setWidth(LABEL_WIDTH);
        label.setMinWidth(LABEL_WIDTH);
        label.getStyle().set(FLEX_SHRINK, NO_SHRINK);
        label.getStyle().set("text-align", "right");
        label.getStyle().set("white-space", "nowrap");

        add(bar, label);
        setWidthFull();
        setPadding(false);
        setSpacing(false);
        setJustifyContentMode(JustifyContentMode.END);
        setDefaultVerticalComponentAlignment(Alignment.CENTER);
        getStyle().set("gap", "var(--lumo-space-s)");
    }

    public ProgressIndicator(BigDecimal percent) {
        this();
        setPercent(percent);
    }

    /**
     * Updates bar and label; the bar value is clamped to 0..100 %, the label
     * always shows the exact percentage.
     */
    public void setPercent(BigDecimal percent) {
        BigDecimal safePercent = percent == null ? BigDecimal.ZERO : percent;
        double fraction = safePercent.divide(HUNDRED, 4, RoundingMode.HALF_UP).doubleValue();
        bar.setValue(Math.max(0, Math.min(1, fraction)));
        label.setText(safePercent + "%");
    }

    ProgressBar getBar() {
        return bar;
    }

    Span getLabel() {
        return label;
    }
}
