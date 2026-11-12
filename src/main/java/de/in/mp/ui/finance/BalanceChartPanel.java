package de.in.mp.ui.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.theme.lumo.LumoUtility;

import de.in.mp.finance.Account;
import de.in.mp.finance.BalanceDateRow;
import de.in.mp.i18n.DateFormatter;
import de.in.mp.i18n.MoneyFormatter;

/**
 * Interactive visualization panel for the historical net worth and its
 * breakdown by account type (stacked area chart with total net worth curve,
 * quick-select period buttons and start/end range sliders).
 */
class BalanceChartPanel extends VerticalLayout {

    private static final int VIEWBOX_WIDTH = 1400;
    private static final int VIEWBOX_HEIGHT = 500;
    private static final double PLOT_LEFT = 115.0;
    private static final double PLOT_RIGHT = 1365.0;
    private static final double PLOT_TOP = 24.0;
    private static final double PLOT_BOTTOM = 440.0;
    private static final double PLOT_WIDTH = PLOT_RIGHT - PLOT_LEFT;
    private static final double PLOT_HEIGHT = PLOT_BOTTOM - PLOT_TOP;
    private static final int MAX_X_TICKS = 9;

    private static final String COLOR_CHECKING = "#3b82f6";
    private static final String COLOR_SAVINGS = "#10b981";
    private static final String COLOR_INVESTMENT = "#8b5cf6";
    private static final String COLOR_TOTAL = "#38bdf8";

    private static final List<Integer> YEAR_PRESETS = List.of(1, 3, 5, 10);
    private static final double[] NICE_STEPS = { 1.0, 2.0, 2.5, 5.0, 10.0 };

    private final Map<Integer, Button> presetButtons = new LinkedHashMap<>();
    private final Button maxPresetButton = new Button();
    private final Span rangeSummary = new Span();
    private final Span deltaBadge = new Span();
    private final Div chartContainer = new Div();
    private final Element startSlider = createRangeInput();
    private final Element endSlider = createRangeInput();
    private final Span startDateLabel = new Span();
    private final Span endDateLabel = new Span();
    private final HorizontalLayout sliderRow = new HorizontalLayout();

    private List<BalanceChartPoint> allPoints = List.of();
    private int startIndex;
    private int endIndex;
    private Integer activeYearsPreset;
    private String lastSvgMarkup = "";

    BalanceChartPanel() {
        setSizeFull();
        setPadding(false);
        setSpacing(true);

        chartContainer.setSizeFull();
        chartContainer.addClassNames(
                LumoUtility.Background.CONTRAST_5,
                LumoUtility.BorderRadius.LARGE,
                LumoUtility.BoxShadow.SMALL);
        chartContainer.getStyle()
                .set("display", "flex")
                .set("flex-direction", "column")
                .set("padding", "var(--lumo-space-m)")
                .set("box-sizing", "border-box")
                .set("min-height", "380px");

        add(createToolbar(), chartContainer, createSliderBar());
        expand(chartContainer);
    }

    void setData(List<Account> accounts, List<BalanceDateRow> rows) {
        this.allPoints = BalanceChartPoint.fromRows(accounts, rows);
        if (allPoints.isEmpty()) {
            startIndex = 0;
            endIndex = 0;
        } else if (activeYearsPreset != null) {
            applyYearsPresetBounds(activeYearsPreset);
        } else {
            startIndex = 0;
            endIndex = allPoints.size() - 1;
        }
        syncSlidersToIndices();
        renderChart();
    }

    void selectYearsPreset(int years) {
        this.activeYearsPreset = years;
        updatePresetButtonStyles();
        if (!allPoints.isEmpty()) {
            applyYearsPresetBounds(years);
            syncSlidersToIndices();
            renderChart();
        }
    }

    void selectMaxPreset() {
        this.activeYearsPreset = null;
        updatePresetButtonStyles();
        if (!allPoints.isEmpty()) {
            startIndex = 0;
            endIndex = allPoints.size() - 1;
            syncSlidersToIndices();
            renderChart();
        }
    }

    void setRangeIndices(int newStart, int newEnd) {
        if (allPoints.isEmpty()) {
            return;
        }
        int maxIdx = allPoints.size() - 1;
        int clampedStart = Math.max(0, Math.min(newStart, maxIdx));
        int clampedEnd = Math.max(clampedStart, Math.min(newEnd, maxIdx));
        this.startIndex = clampedStart;
        this.endIndex = clampedEnd;
        this.activeYearsPreset = null;
        updatePresetButtonStyles();
        syncSlidersToIndices();
        renderChart();
    }

    List<BalanceChartPoint> visiblePointsForTest() {
        if (allPoints.isEmpty()) {
            return List.of();
        }
        return allPoints.subList(startIndex, endIndex + 1);
    }

    String svgMarkupForTest() {
        return lastSvgMarkup;
    }

    private HorizontalLayout createToolbar() {
        HorizontalLayout legend = new HorizontalLayout(
                createLegendItem(COLOR_TOTAL, getTranslation("finance.view.kpi.net-worth"), true),
                createLegendItem(COLOR_INVESTMENT, getTranslation("finance.account.type.INVESTMENT"), false),
                createLegendItem(COLOR_SAVINGS, getTranslation("finance.account.type.SAVINGS"), false),
                createLegendItem(COLOR_CHECKING, getTranslation("finance.account.type.CHECKING"), false));
        legend.setDefaultVerticalComponentAlignment(Alignment.CENTER);
        legend.setSpacing(true);

        rangeSummary.addClassNames(LumoUtility.TextColor.SECONDARY, LumoUtility.FontSize.SMALL);
        deltaBadge.getElement().getThemeList().add("badge");
        deltaBadge.getElement().getThemeList().add("pill");

        HorizontalLayout presets = new HorizontalLayout();
        presets.setSpacing(false);
        presets.getStyle().set("gap", "var(--lumo-space-xs)");
        for (int years : YEAR_PRESETS) {
            Button btn = new Button(getTranslation("finance.chart.range." + years + "y"),
                    event -> selectYearsPreset(years));
            btn.addThemeVariants(ButtonVariant.LUMO_SMALL);
            presetButtons.put(years, btn);
            presets.add(btn);
        }
        maxPresetButton.setText(getTranslation("finance.chart.range.max"));
        maxPresetButton.addThemeVariants(ButtonVariant.LUMO_SMALL);
        maxPresetButton.addClickListener(event -> selectMaxPreset());
        presets.add(maxPresetButton);
        updatePresetButtonStyles();

        HorizontalLayout toolbar = new HorizontalLayout(legend, deltaBadge, rangeSummary, presets);
        toolbar.setWidthFull();
        toolbar.setDefaultVerticalComponentAlignment(Alignment.CENTER);
        toolbar.expand(legend);
        return toolbar;
    }

    private HorizontalLayout createLegendItem(String color, String text, boolean isLine) {
        Span swatch = new Span();
        swatch.getStyle()
                .set("display", "inline-block")
                .set("width", "14px")
                .set("height", isLine ? "3px" : "10px")
                .set("border-radius", "2px")
                .set("background", color);
        Span label = new Span(text);
        label.addClassName(LumoUtility.FontSize.SMALL);
        if (isLine) {
            label.addClassName(LumoUtility.FontWeight.SEMIBOLD);
        }
        HorizontalLayout item = new HorizontalLayout(swatch, label);
        item.setSpacing(false);
        item.getStyle().set("gap", "6px");
        item.setDefaultVerticalComponentAlignment(Alignment.CENTER);
        return item;
    }

    private HorizontalLayout createSliderBar() {
        Span fromCaption = new Span(getTranslation("finance.chart.range.from") + ":");
        fromCaption.addClassNames(LumoUtility.FontSize.SMALL, LumoUtility.TextColor.SECONDARY);
        startDateLabel.addClassNames(LumoUtility.FontSize.SMALL, LumoUtility.FontWeight.MEDIUM);
        startDateLabel.getStyle().set("min-width", "85px");

        Span toCaption = new Span(getTranslation("finance.chart.range.to") + ":");
        toCaption.addClassNames(LumoUtility.FontSize.SMALL, LumoUtility.TextColor.SECONDARY);
        endDateLabel.addClassNames(LumoUtility.FontSize.SMALL, LumoUtility.FontWeight.MEDIUM);
        endDateLabel.getStyle().set("min-width", "85px");

        Div startWrapper = wrapSlider(startSlider);
        Div endWrapper = wrapSlider(endSlider);

        startSlider.addPropertyChangeListener("value", "change", event -> {
            int parsed = parseSliderValue(startSlider.getProperty("value"), 0);
            setRangeIndices(parsed, Math.max(parsed, endIndex));
        });
        endSlider.addPropertyChangeListener("value", "change", event -> {
            int parsed = parseSliderValue(endSlider.getProperty("value"), endIndex);
            setRangeIndices(Math.min(startIndex, parsed), parsed);
        });

        sliderRow.add(fromCaption, startDateLabel, startWrapper, toCaption, endDateLabel, endWrapper);
        sliderRow.setWidthFull();
        sliderRow.setDefaultVerticalComponentAlignment(Alignment.CENTER);
        sliderRow.expand(startWrapper, endWrapper);
        sliderRow.addClassNames(
                LumoUtility.Background.CONTRAST_5,
                LumoUtility.BorderRadius.MEDIUM);
        sliderRow.getStyle()
                .set("padding", "var(--lumo-space-xs) var(--lumo-space-m)")
                .set("box-sizing", "border-box");
        return sliderRow;
    }

    private static Element createRangeInput() {
        Element input = new Element("input");
        input.setAttribute("type", "range");
        input.setAttribute("min", "0");
        input.setAttribute("max", "0");
        input.setAttribute("value", "0");
        input.getStyle()
                .set("width", "100%")
                .set("accent-color", "var(--lumo-primary-color)")
                .set("cursor", "pointer");
        return input;
    }

    private static Div wrapSlider(Element slider) {
        Div wrapper = new Div();
        wrapper.setWidthFull();
        wrapper.getElement().appendChild(slider);
        return wrapper;
    }

    private void applyYearsPresetBounds(int years) {
        endIndex = allPoints.size() - 1;
        LocalDate cutoff = allPoints.get(endIndex).date().minusYears(years);
        int found = 0;
        for (int i = 0; i < allPoints.size(); i++) {
            if (!allPoints.get(i).date().isBefore(cutoff)) {
                found = i;
                break;
            }
        }
        startIndex = found;
    }

    private void syncSlidersToIndices() {
        int maxIdx = Math.max(0, allPoints.size() - 1);
        startSlider.setAttribute("max", String.valueOf(maxIdx));
        endSlider.setAttribute("max", String.valueOf(maxIdx));
        startSlider.setProperty("value", String.valueOf(startIndex));
        endSlider.setProperty("value", String.valueOf(endIndex));
        sliderRow.setVisible(allPoints.size() > 1);
    }

    private void updatePresetButtonStyles() {
        presetButtons.forEach((years, button) -> {
            if (Integer.valueOf(years).equals(activeYearsPreset)) {
                button.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            } else {
                button.removeThemeVariants(ButtonVariant.LUMO_PRIMARY);
            }
        });
        if (activeYearsPreset == null) {
            maxPresetButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        } else {
            maxPresetButton.removeThemeVariants(ButtonVariant.LUMO_PRIMARY);
        }
    }

    private void renderChart() {
        chartContainer.removeAll();
        chartContainer.getElement().removeProperty("innerHTML");
        List<BalanceChartPoint> visible = visiblePointsForTest();
        if (visible.isEmpty()) {
            lastSvgMarkup = "";
            rangeSummary.setText("");
            deltaBadge.setVisible(false);
            startDateLabel.setText("–");
            endDateLabel.setText("–");
            Span empty = new Span(getTranslation("finance.chart.empty"));
            empty.addClassNames(LumoUtility.TextColor.SECONDARY);
            chartContainer.add(empty);
            return;
        }

        BalanceChartPoint firstPoint = visible.get(0);
        BalanceChartPoint lastPoint = visible.get(visible.size() - 1);
        String formattedStart = DateFormatter.format(firstPoint.date(), locale());
        String formattedEnd = DateFormatter.format(lastPoint.date(), locale());
        startDateLabel.setText(formattedStart);
        endDateLabel.setText(formattedEnd);
        rangeSummary.setText(getTranslation("finance.chart.range.summary",
                formattedStart, formattedEnd, visible.size()));
        updateDeltaBadge(firstPoint.total(), lastPoint.total());

        lastSvgMarkup = buildSvg(visible);
        chartContainer.getElement().setProperty("innerHTML", lastSvgMarkup);
    }

    private void updateDeltaBadge(BigDecimal startTotal, BigDecimal endTotal) {
        BigDecimal diff = endTotal.subtract(startTotal);
        String sign = diff.signum() >= 0 ? "+" : "";
        deltaBadge.setText(sign + MoneyFormatter.format(diff, locale()));
        deltaBadge.getElement().getThemeList().remove("success");
        deltaBadge.getElement().getThemeList().remove("error");
        deltaBadge.getElement().getThemeList().add(diff.signum() >= 0 ? "success" : "error");
        deltaBadge.setVisible(true);
    }

    private String buildSvg(List<BalanceChartPoint> points) {
        YScale scale = computeYScale(points);
        double maxDouble = scale.max().doubleValue();

        long minEpoch = points.get(0).date().toEpochDay();
        long maxEpoch = points.get(points.size() - 1).date().toEpochDay();

        double[] xs = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            xs[i] = xFor(points.get(i).date().toEpochDay(), minEpoch, maxEpoch);
        }

        StringBuilder svg = new StringBuilder(8192);
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100%\" height=\"100%\" viewBox=\"0 0 ")
                .append(VIEWBOX_WIDTH).append(' ').append(VIEWBOX_HEIGHT)
                .append("\" preserveAspectRatio=\"xMidYMid meet\" ")
                .append("style=\"flex:1 1 auto;width:100%;height:100%;display:block;font-family:var(--lumo-font-family);\">");

        appendDefs(svg);
        appendYGridAndLabels(svg, scale);
        appendXGridAndLabels(svg, points, xs);

        appendStackedArea(svg, "CHECKING", "url(#mp-grad-checking)", COLOR_CHECKING, points, xs, maxDouble,
                p -> BigDecimal.ZERO, BalanceChartPoint::checking);
        appendStackedArea(svg, "SAVINGS", "url(#mp-grad-savings)", COLOR_SAVINGS, points, xs, maxDouble,
                BalanceChartPoint::checking, BalanceChartPoint::stackedSavings);
        appendStackedArea(svg, "INVESTMENT", "url(#mp-grad-investment)", COLOR_INVESTMENT, points, xs, maxDouble,
                BalanceChartPoint::stackedSavings, BalanceChartPoint::stackedInvestment);

        appendAxesLines(svg);
        appendTotalLineAndMarkers(svg, points, xs, maxDouble);

        svg.append("</svg>");
        return svg.toString();
    }

    private static void appendDefs(StringBuilder svg) {
        svg.append("<defs>")
                .append(verticalGradient("mp-grad-checking", COLOR_CHECKING, "0.55", "0.18"))
                .append(verticalGradient("mp-grad-savings", COLOR_SAVINGS, "0.55", "0.18"))
                .append(verticalGradient("mp-grad-investment", COLOR_INVESTMENT, "0.60", "0.20"))
                .append("</defs>")
                .append("<style>")
                .append(".mp-pt{transition:r 0.12s ease,stroke-width 0.12s ease;cursor:pointer;}")
                .append(".mp-pt:hover{r:7px;stroke-width:2.5px;}")
                .append("</style>");
    }

    private static String verticalGradient(String id, String color, String topOpacity, String bottomOpacity) {
        return "<linearGradient id=\"" + id + "\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\">"
                + "<stop offset=\"0%\" stop-color=\"" + color + "\" stop-opacity=\"" + topOpacity + "\"/>"
                + "<stop offset=\"100%\" stop-color=\"" + color + "\" stop-opacity=\"" + bottomOpacity + "\"/>"
                + "</linearGradient>";
    }

    private void appendYGridAndLabels(StringBuilder svg, YScale scale) {
        double maxDouble = scale.max().doubleValue();
        for (int i = 0; i <= scale.tickCount(); i++) {
            BigDecimal tickValue = scale.step().multiply(BigDecimal.valueOf(i));
            double y = yFor(tickValue.doubleValue(), maxDouble);
            svg.append("<line x1=\"").append(fmt(PLOT_LEFT))
                    .append("\" y1=\"").append(fmt(y))
                    .append("\" x2=\"").append(fmt(PLOT_RIGHT))
                    .append("\" y2=\"").append(fmt(y))
                    .append("\" stroke=\"var(--lumo-contrast-10pct)\" stroke-dasharray=\"4 4\" stroke-width=\"1\"/>");
            svg.append("<text x=\"").append(fmt(PLOT_LEFT - 12))
                    .append("\" y=\"").append(fmt(y + 4))
                    .append("\" text-anchor=\"end\" fill=\"var(--lumo-secondary-text-color)\" font-size=\"12\" font-weight=\"500\">")
                    .append(escapeXml(formatAxisMoney(tickValue)))
                    .append("</text>");
        }
    }

    private void appendXGridAndLabels(StringBuilder svg, List<BalanceChartPoint> points, double[] xs) {
        List<Integer> tickIndices = selectTickIndices(points.size(), MAX_X_TICKS);
        for (int idx : tickIndices) {
            double x = xs[idx];
            String label = DateFormatter.format(points.get(idx).date(), locale());
            svg.append("<line x1=\"").append(fmt(x))
                    .append("\" y1=\"").append(fmt(PLOT_TOP))
                    .append("\" x2=\"").append(fmt(x))
                    .append("\" y2=\"").append(fmt(PLOT_BOTTOM))
                    .append("\" stroke=\"var(--lumo-contrast-10pct)\" stroke-dasharray=\"3 3\" stroke-width=\"1\"/>");
            svg.append("<line x1=\"").append(fmt(x))
                    .append("\" y1=\"").append(fmt(PLOT_BOTTOM))
                    .append("\" x2=\"").append(fmt(x))
                    .append("\" y2=\"").append(fmt(PLOT_BOTTOM + 6))
                    .append("\" stroke=\"var(--lumo-contrast-30pct)\" stroke-width=\"1.5\"/>");
            svg.append("<text x=\"").append(fmt(x))
                    .append("\" y=\"").append(fmt(PLOT_BOTTOM + 24))
                    .append("\" text-anchor=\"middle\" fill=\"var(--lumo-secondary-text-color)\" font-size=\"12\" font-weight=\"500\">")
                    .append(escapeXml(label))
                    .append("</text>");
        }
    }

    private static void appendAxesLines(StringBuilder svg) {
        svg.append("<line x1=\"").append(fmt(PLOT_LEFT))
                .append("\" y1=\"").append(fmt(PLOT_TOP))
                .append("\" x2=\"").append(fmt(PLOT_LEFT))
                .append("\" y2=\"").append(fmt(PLOT_BOTTOM))
                .append("\" stroke=\"var(--lumo-contrast-30pct)\" stroke-width=\"1.5\"/>");
        svg.append("<line x1=\"").append(fmt(PLOT_LEFT))
                .append("\" y1=\"").append(fmt(PLOT_BOTTOM))
                .append("\" x2=\"").append(fmt(PLOT_RIGHT))
                .append("\" y2=\"").append(fmt(PLOT_BOTTOM))
                .append("\" stroke=\"var(--lumo-contrast-30pct)\" stroke-width=\"1.5\"/>");
    }

    private void appendStackedArea(
            StringBuilder svg,
            String seriesKey,
            String fill,
            String stroke,
            List<BalanceChartPoint> points,
            double[] xs,
            double maxDouble,
            Function<BalanceChartPoint, BigDecimal> lowerExtractor,
            Function<BalanceChartPoint, BigDecimal> upperExtractor) {
        if (points.size() == 1) {
            double x = xs[0];
            double yTop = yFor(upperExtractor.apply(points.get(0)).doubleValue(), maxDouble);
            double yBottom = yFor(lowerExtractor.apply(points.get(0)).doubleValue(), maxDouble);
            svg.append("<line data-series=\"").append(seriesKey)
                    .append("\" x1=\"").append(fmt(x)).append("\" y1=\"").append(fmt(yBottom))
                    .append("\" x2=\"").append(fmt(x)).append("\" y2=\"").append(fmt(yTop))
                    .append("\" stroke=\"").append(stroke).append("\" stroke-width=\"20\"/>");
            return;
        }

        StringBuilder areaPath = new StringBuilder();
        StringBuilder topEdgePath = new StringBuilder();
        for (int i = 0; i < points.size(); i++) {
            double x = xs[i];
            double yTop = yFor(upperExtractor.apply(points.get(i)).doubleValue(), maxDouble);
            areaPath.append(i == 0 ? "M " : " L ").append(fmt(x)).append(' ').append(fmt(yTop));
            topEdgePath.append(i == 0 ? "M " : " L ").append(fmt(x)).append(' ').append(fmt(yTop));
        }
        for (int i = points.size() - 1; i >= 0; i--) {
            double x = xs[i];
            double yBottom = yFor(lowerExtractor.apply(points.get(i)).doubleValue(), maxDouble);
            areaPath.append(" L ").append(fmt(x)).append(' ').append(fmt(yBottom));
        }
        areaPath.append(" Z");

        svg.append("<path data-series=\"").append(seriesKey)
                .append("\" d=\"").append(areaPath)
                .append("\" fill=\"").append(fill).append("\" stroke=\"none\"/>");
        svg.append("<path d=\"").append(topEdgePath)
                .append("\" fill=\"none\" stroke=\"").append(stroke)
                .append("\" stroke-width=\"1.75\" stroke-linejoin=\"round\"/>");
    }

    private void appendTotalLineAndMarkers(StringBuilder svg, List<BalanceChartPoint> points, double[] xs,
            double maxDouble) {
        if (points.size() > 1) {
            StringBuilder totalPath = new StringBuilder();
            for (int i = 0; i < points.size(); i++) {
                double x = xs[i];
                double y = yFor(points.get(i).total().doubleValue(), maxDouble);
                totalPath.append(i == 0 ? "M " : " L ").append(fmt(x)).append(' ').append(fmt(y));
            }
            svg.append("<path data-series=\"TOTAL\" d=\"").append(totalPath)
                    .append("\" fill=\"none\" stroke=\"").append(COLOR_TOTAL)
                    .append("\" stroke-width=\"3\" stroke-linejoin=\"round\" stroke-linecap=\"round\"/>");
        } else {
            svg.append("<g data-series=\"TOTAL\"/>");
        }

        String radius = points.size() > 60 ? "3" : "4.5";
        for (int i = 0; i < points.size(); i++) {
            BalanceChartPoint point = points.get(i);
            double x = xs[i];
            double y = yFor(point.total().doubleValue(), maxDouble);
            svg.append("<circle class=\"mp-pt\" cx=\"").append(fmt(x))
                    .append("\" cy=\"").append(fmt(y))
                    .append("\" r=\"").append(radius)
                    .append("\" fill=\"").append(COLOR_TOTAL)
                    .append("\" stroke=\"var(--lumo-base-color)\" stroke-width=\"1.5\">")
                    .append("<title>").append(escapeXml(buildPointTooltip(point))).append("</title>")
                    .append("</circle>");
        }
    }

    private String buildPointTooltip(BalanceChartPoint point) {
        Locale loc = locale();
        return DateFormatter.format(point.date(), loc) + "\n"
                + getTranslation("finance.view.kpi.net-worth") + ": " + MoneyFormatter.format(point.total(), loc) + "\n"
                + getTranslation("finance.account.type.INVESTMENT") + ": " + MoneyFormatter.format(point.investment(), loc) + "\n"
                + getTranslation("finance.account.type.SAVINGS") + ": " + MoneyFormatter.format(point.savings(), loc) + "\n"
                + getTranslation("finance.account.type.CHECKING") + ": " + MoneyFormatter.format(point.checking(), loc);
    }

    private static List<Integer> selectTickIndices(int totalPoints, int maxTicks) {
        List<Integer> indices = new ArrayList<>();
        if (totalPoints <= maxTicks) {
            for (int i = 0; i < totalPoints; i++) {
                indices.add(i);
            }
            return indices;
        }
        for (int t = 0; t < maxTicks; t++) {
            int idx = (int) Math.round((double) t * (totalPoints - 1) / (maxTicks - 1));
            if (indices.isEmpty() || indices.get(indices.size() - 1) != idx) {
                indices.add(idx);
            }
        }
        return indices;
    }

    private static YScale computeYScale(List<BalanceChartPoint> points) {
        BigDecimal max = BigDecimal.ZERO;
        for (BalanceChartPoint point : points) {
            max = max.max(point.total()).max(point.stackedInvestment());
        }
        if (max.signum() <= 0) {
            return new YScale(new BigDecimal("250"), 4, new BigDecimal("1000"));
        }
        double targetMax = max.doubleValue() * 1.05;
        double rawStep = targetMax / 5.0;
        double magnitude = Math.pow(10, Math.floor(Math.log10(rawStep)));
        double normalized = rawStep / magnitude;
        double chosenStep = 10.0 * magnitude;
        for (double candidate : NICE_STEPS) {
            if (normalized <= candidate) {
                chosenStep = candidate * magnitude;
                break;
            }
        }
        BigDecimal step = BigDecimal.valueOf(Math.max(1L, Math.round(chosenStep)));
        int ticks = Math.max(4, (int) Math.ceil(targetMax / step.doubleValue()));
        return new YScale(step, ticks, step.multiply(BigDecimal.valueOf(ticks)));
    }

    private record YScale(BigDecimal step, int tickCount, BigDecimal max) {
    }

    private static String formatAxisMoney(BigDecimal amount) {
        NumberFormat format = NumberFormat.getCurrencyInstance(locale());
        format.setMaximumFractionDigits(0);
        format.setMinimumFractionDigits(0);
        return format.format(amount.setScale(0, RoundingMode.HALF_UP));
    }

    private static double xFor(long epochDay, long minEpoch, long maxEpoch) {
        if (maxEpoch <= minEpoch) {
            return PLOT_LEFT + PLOT_WIDTH / 2.0;
        }
        return PLOT_LEFT + ((double) (epochDay - minEpoch) / (maxEpoch - minEpoch)) * PLOT_WIDTH;
    }

    private static double yFor(double value, double maxDouble) {
        if (maxDouble <= 0) {
            return PLOT_BOTTOM;
        }
        double clamped = Math.max(0.0, Math.min(value, maxDouble));
        return PLOT_BOTTOM - (clamped / maxDouble) * PLOT_HEIGHT;
    }

    private static String fmt(double val) {
        return String.format(Locale.ROOT, "%.1f", val);
    }

    private static int parseSliderValue(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String escapeXml(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static Locale locale() {
        return UI.getCurrent() != null ? UI.getCurrent().getLocale() : Locale.GERMANY;
    }
}
