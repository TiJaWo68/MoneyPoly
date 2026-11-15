package de.in.mp.ui.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.theme.lumo.LumoUtility;

import de.in.mp.finance.exitplan.ExitPlanPersonInput;
import de.in.mp.finance.exitplan.ExitPlanRequest;
import de.in.mp.finance.exitplan.ExitPlanResult;
import de.in.mp.finance.exitplan.ExitPlanYearRow;
import de.in.mp.i18n.MoneyFormatter;

/**
 * Interactive SVG projection chart displaying household capital over age
 * with milestone markers for work exit, statutory pension start and target age.
 */
class ExitPlanChartPanel extends VerticalLayout {

    private static final int VIEWBOX_WIDTH = 1200;
    private static final int VIEWBOX_HEIGHT = 360;
    private static final double PLOT_LEFT = 105.0;
    private static final double PLOT_RIGHT = 1165.0;
    private static final double PLOT_TOP = 28.0;
    private static final double PLOT_BOTTOM = 308.0;
    private static final double PLOT_WIDTH = PLOT_RIGHT - PLOT_LEFT;
    private static final double PLOT_HEIGHT = PLOT_BOTTOM - PLOT_TOP;
    private static final int Y_TICKS = 5;
    private static final double[] NICE_STEPS = { 1.0, 2.0, 2.5, 5.0, 10.0 };

    private static final String COLOR_LINE = "#38bdf8";
    private static final String COLOR_MILESTONE_EXIT = "#f59e0b";
    private static final String COLOR_MILESTONE_PENSION = "#10b981";
    private static final String COLOR_TARGET = "#a855f7";

    private final Div chartContainer = new Div();
    private String lastSvgMarkup = "";

    ExitPlanChartPanel() {
        setWidthFull();
        setPadding(false);
        setSpacing(false);

        chartContainer.setWidthFull();
        chartContainer.addClassNames(
                LumoUtility.Background.CONTRAST_5,
                LumoUtility.BorderRadius.LARGE,
                LumoUtility.BoxShadow.SMALL);
        chartContainer.getStyle()
                .set("display", "flex")
                .set("flex-direction", "column")
                .set("padding", "var(--lumo-space-m)")
                .set("box-sizing", "border-box")
                .set("min-height", "260px");

        add(chartContainer);
    }

    void setData(ExitPlanRequest request, ExitPlanResult result) {
        chartContainer.removeAll();
        chartContainer.getElement().removeProperty("innerHTML");
        if (request == null || result == null || result.rows().isEmpty()) {
            lastSvgMarkup = "";
            Span empty = new Span(getTranslation("finance.exit-plan.chart.empty"));
            empty.addClassNames(LumoUtility.TextColor.SECONDARY);
            chartContainer.add(empty);
            return;
        }
        lastSvgMarkup = buildSvg(request, result.rows());
        chartContainer.getElement().setProperty("innerHTML", lastSvgMarkup);
    }

    String getLastSvgMarkupForTest() {
        return lastSvgMarkup;
    }

    private String buildSvg(ExitPlanRequest request, List<ExitPlanYearRow> rows) {
        BigDecimal maxVal = rows.stream()
                .map(ExitPlanYearRow::endCapital)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
        if (request.startingCapital() != null && request.startingCapital().compareTo(maxVal) > 0) {
            maxVal = request.startingCapital();
        }
        double yStep = niceStep(maxVal.doubleValue());
        double yMax = Math.max(yStep * Y_TICKS, 1000.0);

        int count = rows.size();
        double[] xs = new double[count];
        double[] ys = new double[count];
        for (int i = 0; i < count; i++) {
            xs[i] = count == 1 ? PLOT_LEFT + PLOT_WIDTH / 2.0
                    : PLOT_LEFT + ((double) i / (count - 1)) * PLOT_WIDTH;
            ys[i] = yFor(rows.get(i).endCapital().doubleValue(), yMax);
        }

        StringBuilder svg = new StringBuilder(6144);
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100%\" height=\"100%\" viewBox=\"0 0 ")
                .append(VIEWBOX_WIDTH).append(' ').append(VIEWBOX_HEIGHT)
                .append("\" preserveAspectRatio=\"xMidYMid meet\" ")
                .append("style=\"flex:1 1 auto;width:100%;height:auto;display:block;font-family:var(--lumo-font-family);\">");

        svg.append("<defs><linearGradient id=\"mp-exit-grad\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\">")
                .append("<stop offset=\"0%\" stop-color=\"").append(COLOR_LINE).append("\" stop-opacity=\"0.45\"/>")
                .append("<stop offset=\"100%\" stop-color=\"").append(COLOR_LINE).append("\" stop-opacity=\"0.05\"/>")
                .append("</linearGradient></defs>");

        appendYGrid(svg, yStep, yMax);
        appendXGrid(svg, rows, xs);
        appendMilestones(svg, request, rows, xs);
        appendAreaAndLine(svg, rows, xs, ys);

        svg.append("</svg>");
        return svg.toString();
    }

    private void appendYGrid(StringBuilder svg, double yStep, double yMax) {
        for (int i = 0; i <= Y_TICKS; i++) {
            double val = yStep * i;
            double y = yFor(val, yMax);
            svg.append("<line x1=\"").append(fmt(PLOT_LEFT)).append("\" y1=\"").append(fmt(y))
                    .append("\" x2=\"").append(fmt(PLOT_RIGHT)).append("\" y2=\"").append(fmt(y))
                    .append("\" stroke=\"var(--lumo-contrast-10pct)\" stroke-dasharray=\"4 4\" stroke-width=\"1\"/>");
            svg.append("<text x=\"").append(fmt(PLOT_LEFT - 10)).append("\" y=\"").append(fmt(y + 4))
                    .append("\" text-anchor=\"end\" fill=\"var(--lumo-secondary-text-color)\" font-size=\"12\">")
                    .append(escapeXml(formatShortMoney(BigDecimal.valueOf(val))))
                    .append("</text>");
        }
    }

    private void appendXGrid(StringBuilder svg, List<ExitPlanYearRow> rows, double[] xs) {
        int step = Math.max(1, rows.size() / 8);
        for (int i = 0; i < rows.size(); i += step) {
            double x = xs[i];
            ExitPlanYearRow row = rows.get(i);
            String label = row.primaryAge() + " (" + row.calendarYear() + ")";
            svg.append("<text x=\"").append(fmt(x)).append("\" y=\"").append(fmt(PLOT_BOTTOM + 22))
                    .append("\" text-anchor=\"middle\" fill=\"var(--lumo-secondary-text-color)\" font-size=\"12\">")
                    .append(escapeXml(label))
                    .append("</text>");
        }
    }

    private void appendMilestones(StringBuilder svg, ExitPlanRequest request, List<ExitPlanYearRow> rows, double[] xs) {
        ExitPlanPersonInput primary = request.primaryPerson();
        appendMilestoneByAge(svg, rows, xs, primary.exitAge(),
                getTranslation("finance.exit-plan.milestone.exit"), COLOR_MILESTONE_EXIT, 0);
        appendMilestoneByAge(svg, rows, xs, primary.pensionAge(),
                getTranslation("finance.exit-plan.milestone.pension", primary.pensionAge()), COLOR_MILESTONE_PENSION, 16);
        appendMilestoneByAge(svg, rows, xs, request.targetAge(),
                getTranslation("finance.exit-plan.milestone.target", request.targetAge()), COLOR_TARGET, 0);
    }

    private void appendMilestoneByAge(
            StringBuilder svg,
            List<ExitPlanYearRow> rows,
            double[] xs,
            int targetPrimaryAge,
            String label,
            String color,
            double labelOffsetY) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).primaryAge() == targetPrimaryAge) {
                double x = xs[i];
                svg.append("<line x1=\"").append(fmt(x)).append("\" y1=\"").append(fmt(PLOT_TOP))
                        .append("\" x2=\"").append(fmt(x)).append("\" y2=\"").append(fmt(PLOT_BOTTOM))
                        .append("\" stroke=\"").append(color)
                        .append("\" stroke-dasharray=\"5 4\" stroke-width=\"1.5\"/>");
                svg.append("<text x=\"").append(fmt(x + 4)).append("\" y=\"").append(fmt(PLOT_TOP + 12 + labelOffsetY))
                        .append("\" fill=\"").append(color).append("\" font-size=\"11\" font-weight=\"600\">")
                        .append(escapeXml(label))
                        .append("</text>");
                break;
            }
        }
    }

    private void appendAreaAndLine(StringBuilder svg, List<ExitPlanYearRow> rows, double[] xs, double[] ys) {
        StringBuilder area = new StringBuilder();
        StringBuilder line = new StringBuilder();
        area.append("M ").append(fmt(xs[0])).append(' ').append(fmt(PLOT_BOTTOM));
        for (int i = 0; i < rows.size(); i++) {
            area.append(" L ").append(fmt(xs[i])).append(' ').append(fmt(ys[i]));
            line.append(i == 0 ? "M " : " L ").append(fmt(xs[i])).append(' ').append(fmt(ys[i]));
        }
        area.append(" L ").append(fmt(xs[rows.size() - 1])).append(' ').append(fmt(PLOT_BOTTOM)).append(" Z");

        svg.append("<path d=\"").append(area).append("\" fill=\"url(#mp-exit-grad)\"/>");
        svg.append("<path d=\"").append(line).append("\" fill=\"none\" stroke=\"").append(COLOR_LINE)
                .append("\" stroke-width=\"2.5\" stroke-linejoin=\"round\" stroke-linecap=\"round\"/>");

        for (int i = 0; i < rows.size(); i++) {
            ExitPlanYearRow row = rows.get(i);
            String tooltip = row.calendarYear() + " (" + row.primaryAge() + "): "
                    + MoneyFormatter.format(row.endCapital(), locale());
            svg.append("<circle cx=\"").append(fmt(xs[i])).append("\" cy=\"").append(fmt(ys[i]))
                    .append("\" r=\"3.5\" fill=\"").append(COLOR_LINE)
                    .append("\" stroke=\"var(--lumo-base-color)\" stroke-width=\"1.5\">")
                    .append("<title>").append(escapeXml(tooltip)).append("</title></circle>");
        }
    }

    private static double niceStep(double maxVal) {
        if (maxVal <= 0) {
            return 1000.0;
        }
        double rawStep = maxVal / Y_TICKS;
        double magnitude = Math.pow(10, Math.floor(Math.log10(rawStep)));
        for (double factor : NICE_STEPS) {
            if (factor * magnitude >= rawStep) {
                return factor * magnitude;
            }
        }
        return 10.0 * magnitude;
    }

    private static double yFor(double value, double yMax) {
        double ratio = Math.max(0.0, Math.min(1.0, value / yMax));
        return PLOT_BOTTOM - ratio * PLOT_HEIGHT;
    }

    private String formatShortMoney(BigDecimal value) {
        NumberFormat fmt = NumberFormat.getCurrencyInstance(locale());
        fmt.setMaximumFractionDigits(0);
        return fmt.format(value.setScale(0, RoundingMode.HALF_UP));
    }

    private static String fmt(double val) {
        return String.format(Locale.US, "%.2f", val);
    }

    private static String escapeXml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private Locale locale() {
        return UI.getCurrent() != null ? UI.getCurrent().getLocale() : Locale.GERMANY;
    }
}
