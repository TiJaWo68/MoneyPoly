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
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.theme.lumo.LumoUtility;

import de.in.mp.finance.Account;
import de.in.mp.finance.BalanceDateRow;
import de.in.mp.finance.DepotValuation;
import de.in.mp.finance.WealthEvent;
import de.in.mp.i18n.DateFormatter;
import de.in.mp.i18n.MoneyFormatter;

/**
 * Interactive visualization panel for the historical net worth and its
 * breakdown by account type (stacked area chart with total net worth curve,
 * quick-select period buttons, drag-box/wheel zoom, pan/scale controls, and
 * start/end range sliders).
 */
class BalanceChartPanel extends VerticalLayout {

    private static final int VIEWBOX_WIDTH = 1400;
    private static final int VIEWBOX_HEIGHT = 590;
    private static final double PLOT_LEFT = 115.0;
    private static final double PLOT_RIGHT = 1365.0;
    private static final double PLOT_TOP = 22.0;
    private static final double PLOT_BOTTOM = 382.0;
    private static final double PLOT_WIDTH = PLOT_RIGHT - PLOT_LEFT;
    private static final double PLOT_HEIGHT = PLOT_BOTTOM - PLOT_TOP;
    private static final double DELTA_TOP = 406.0;
    private static final double DELTA_BOTTOM = 532.0;
    private static final double DELTA_HEIGHT = DELTA_BOTTOM - DELTA_TOP;
    private static final int MAX_X_TICKS = 9;
    private static final int MIN_VISIBLE_POINTS = 2;
    private static final double ZOOM_STEP_FACTOR = 0.65;
    private static final double PAN_STEP_FACTOR = 0.25;

    private static final String COLOR_CHECKING = "#3b82f6";
    private static final String COLOR_SAVINGS = "#10b981";
    private static final String COLOR_INVESTMENT = "#8b5cf6";
    private static final String COLOR_TOTAL = "#38bdf8";
    private static final String COLOR_INVESTED_CAPITAL = "#c084fc";
    private static final String COLOR_WEALTH_EVENT = "#f59e0b";
    private static final String COLOR_LOAN = "#f43f5e";
    private static final String COLOR_DELTA_POS = "#10b981";
    private static final String COLOR_DELTA_NEG = "#f43f5e";

    record ChartInterval(LocalDate previousDate, LocalDate targetDate, BigDecimal netWorthDelta) {
    }

    private static final List<Integer> MONTH_PRESETS = List.of(3, 6);
    private static final List<Integer> YEAR_PRESETS = List.of(1, 3, 5, 10);
    private static final double[] NICE_STEPS = { 1.0, 1.5, 2.0, 2.5, 5.0, 10.0 };
    private static final int MIN_Y_TICKS = 4;
    private static final int MAX_Y_TICKS = 7;

    private final Map<Integer, Button> monthPresetButtons = new LinkedHashMap<>();
    private final Map<Integer, Button> yearPresetButtons = new LinkedHashMap<>();
    private final Button maxPresetButton = new Button();
    private final Span rangeSummary = new Span();
    private final Span deltaBadge = new Span();
    private final Div chartContainer = new Div();
    private final Element startSlider = createRangeInput();
    private final Element endSlider = createRangeInput();
    private final Span startDateLabel = new Span();
    private final Span endDateLabel = new Span();
    private final HorizontalLayout sliderRow = new HorizontalLayout();
    private HorizontalLayout investedLegendItem;
    private HorizontalLayout loansLegendItem;
    private HorizontalLayout eventsLegendItem;
    private Button manageEventsButton;
    private Button yScaleButton;
    private Button xScaleButton;
    private Button panLeftButton;
    private Button panRightButton;
    private Button zoomOutButton;
    private Button zoomInButton;

    private List<BalanceChartPoint> allPoints = List.of();
    private List<WealthEvent> wealthEvents = List.of();
    private Consumer<ChartInterval> onIntervalDoubleClick;
    private Runnable onManageEventsClick;
    private int startIndex;
    private int endIndex;
    private Integer activeMonthsPreset;
    private Integer activeYearsPreset;
    private boolean customZoomActive;
    private boolean yFocusEnabled;
    private boolean evenPointSpacing;
    private String lastSvgMarkup = "";

    BalanceChartPanel() {
        setSizeFull();
        setPadding(false);
        setSpacing(false);
        getStyle().set("gap", "var(--lumo-space-xs)");

        chartContainer.setSizeFull();
        chartContainer.addClassNames(
                LumoUtility.Background.CONTRAST_5,
                LumoUtility.BorderRadius.LARGE,
                LumoUtility.BoxShadow.SMALL);
        chartContainer.getStyle()
                .set("display", "flex")
                .set("flex-direction", "column")
                .set("padding", "var(--lumo-space-s) var(--lumo-space-m)")
                .set("box-sizing", "border-box")
                .set("min-height", "360px")
                .set("user-select", "none");
        attachChartInteractions();

        add(createHeaderBar(), createControlsBar(), chartContainer, createSliderBar());
        expand(chartContainer);
    }

    private void attachChartInteractions() {
        chartContainer.getElement().executeJs(
                """
                const host = $0;
                const PLOT_LEFT = 115.0;
                const PLOT_RIGHT = 1365.0;
                const PLOT_TOP = 22.0;
                const PLOT_HEIGHT = 510.0;
                const PLOT_WIDTH = PLOT_RIGHT - PLOT_LEFT;
                const SVG_NS = 'http://www.w3.org/2000/svg';

                const toViewBoxX = (svg, clientX) => {
                  const ctm = svg.getScreenCTM();
                  if (ctm && ctm.a !== 0) {
                    return (clientX - ctm.e) / ctm.a;
                  }
                  const rect = svg.getBoundingClientRect();
                  return ((clientX - rect.left) / Math.max(1, rect.width)) * 1400.0;
                };

                const toPlotRatio = (vbX) => {
                  return Math.max(0.0, Math.min(1.0, (vbX - PLOT_LEFT) / PLOT_WIDTH));
                };

                let dragStartClientX = null;
                let dragStartVbX = null;
                let dragRect = null;
                let lastDragAt = 0;

                const clearDrag = () => {
                  dragStartClientX = null;
                  dragStartVbX = null;
                  if (dragRect && dragRect.parentNode) {
                    dragRect.parentNode.removeChild(dragRect);
                  }
                  dragRect = null;
                };

                this.addEventListener('mousedown', (e) => {
                  if (e.button !== 0) return;
                  const svg = this.querySelector('svg');
                  if (!svg) return;
                  const vbX = toViewBoxX(svg, e.clientX);
                  if (vbX < PLOT_LEFT || vbX > PLOT_RIGHT) return;
                  dragStartClientX = e.clientX;
                  dragStartVbX = Math.max(PLOT_LEFT, Math.min(PLOT_RIGHT, vbX));
                });

                window.addEventListener('mousemove', (e) => {
                  if (!this.isConnected) {
                    clearDrag();
                    return;
                  }
                  if (dragStartClientX === null) return;
                  const svg = this.querySelector('svg');
                  if (!svg) {
                    clearDrag();
                    return;
                  }
                  if (Math.abs(e.clientX - dragStartClientX) < 10) return;
                  e.preventDefault();
                  const curVbX = Math.max(PLOT_LEFT, Math.min(PLOT_RIGHT, toViewBoxX(svg, e.clientX)));
                  if (!dragRect) {
                    dragRect = document.createElementNS(SVG_NS, 'rect');
                    dragRect.setAttribute('y', String(PLOT_TOP));
                    dragRect.setAttribute('height', String(PLOT_HEIGHT));
                    dragRect.setAttribute('fill', 'rgba(56, 189, 248, 0.18)');
                    dragRect.setAttribute('stroke', '#38bdf8');
                    dragRect.setAttribute('stroke-width', '1.5');
                    dragRect.setAttribute('stroke-dasharray', '4 3');
                    dragRect.setAttribute('pointer-events', 'none');
                    svg.appendChild(dragRect);
                  }
                  const x = Math.min(dragStartVbX, curVbX);
                  const w = Math.max(2, Math.abs(curVbX - dragStartVbX));
                  dragRect.setAttribute('x', x.toFixed(1));
                  dragRect.setAttribute('width', w.toFixed(1));
                });

                window.addEventListener('mouseup', (e) => {
                  if (!this.isConnected) {
                    clearDrag();
                    return;
                  }
                  if (dragStartClientX === null) return;
                  const svg = this.querySelector('svg');
                  const movedPx = Math.abs(e.clientX - dragStartClientX);
                  const startVb = dragStartVbX;
                  clearDrag();
                  if (!svg || movedPx < 12) return;
                  lastDragAt = Date.now();
                  const endVb = Math.max(PLOT_LEFT, Math.min(PLOT_RIGHT, toViewBoxX(svg, e.clientX)));
                  const r1 = toPlotRatio(Math.min(startVb, endVb));
                  const r2 = toPlotRatio(Math.max(startVb, endVb));
                  if (host.$server && host.$server.handlePlotRangeZoom) {
                    host.$server.handlePlotRangeZoom(r1, r2);
                  }
                });

                let wheelTimer = null;
                let wheelAccumDelta = 0;
                let wheelLastRatio = 0.5;
                this.addEventListener('wheel', (e) => {
                  const svg = this.querySelector('svg');
                  if (!svg) return;
                  e.preventDefault();
                  wheelLastRatio = toPlotRatio(toViewBoxX(svg, e.clientX));
                  wheelAccumDelta += e.deltaY;
                  if (wheelTimer !== null) return;
                  wheelTimer = setTimeout(() => {
                    const delta = wheelAccumDelta;
                    const ratio = wheelLastRatio;
                    wheelAccumDelta = 0;
                    wheelTimer = null;
                    if (delta !== 0 && host.$server && host.$server.handlePlotWheelZoom) {
                      host.$server.handlePlotWheelZoom(ratio, delta < 0);
                    }
                  }, 60);
                }, { passive: false });

                this.addEventListener('dblclick', (e) => {
                  if (Date.now() - lastDragAt < 250) return;
                  const target = e.target && e.target.closest ? e.target.closest('.mp-pt, .mp-seg, .mp-delta') : null;
                  if (!target) return;
                  const raw = target.getAttribute('data-idx');
                  if (raw === null) return;
                  const idx = parseInt(raw, 10);
                  if (!Number.isNaN(idx) && host.$server && host.$server.handleChartIntervalDoubleClick) {
                    host.$server.handleChartIntervalDoubleClick(idx);
                  }
                });
                """,
                getElement());
    }

    void setChartInteractionCallbacks(
            Consumer<ChartInterval> onIntervalDoubleClick,
            Runnable onManageEventsClick) {
        this.onIntervalDoubleClick = onIntervalDoubleClick;
        this.onManageEventsClick = onManageEventsClick;
        if (manageEventsButton != null) {
            manageEventsButton.setVisible(onManageEventsClick != null);
        }
    }

    void setData(List<Account> accounts, List<BalanceDateRow> rows) {
        setData(accounts, rows, Map.of(), List.of());
    }

    void setData(List<Account> accounts, List<BalanceDateRow> rows, Map<UUID, DepotValuation> liveDepots) {
        setData(accounts, rows, liveDepots, List.of());
    }

    void setData(
            List<Account> accounts,
            List<BalanceDateRow> rows,
            Map<UUID, DepotValuation> liveDepots,
            List<WealthEvent> wealthEvents) {
        LocalDate prevStartDate = customZoomActive && !this.allPoints.isEmpty() && startIndex < this.allPoints.size()
                ? this.allPoints.get(startIndex).date()
                : null;
        LocalDate prevEndDate = customZoomActive && !this.allPoints.isEmpty() && endIndex < this.allPoints.size()
                ? this.allPoints.get(endIndex).date()
                : null;

        this.allPoints = BalanceChartPoint.fromRows(accounts, rows, liveDepots);
        this.wealthEvents = wealthEvents != null ? List.copyOf(wealthEvents) : List.of();
        if (allPoints.isEmpty()) {
            startIndex = 0;
            endIndex = 0;
            customZoomActive = false;
        } else if (activeMonthsPreset != null) {
            applyMonthsPresetBounds(activeMonthsPreset);
        } else if (activeYearsPreset != null) {
            applyYearsPresetBounds(activeYearsPreset);
        } else if (customZoomActive && prevStartDate != null && prevEndDate != null) {
            restoreCustomDateBounds(prevStartDate, prevEndDate);
        } else {
            startIndex = 0;
            endIndex = allPoints.size() - 1;
        }
        syncSlidersToIndices();
        updateControlStates();
        renderChart();
    }

    void selectMonthsPreset(int months) {
        this.activeMonthsPreset = months;
        this.activeYearsPreset = null;
        this.customZoomActive = false;
        updatePresetButtonStyles();
        if (!allPoints.isEmpty()) {
            applyMonthsPresetBounds(months);
            syncSlidersToIndices();
            updateControlStates();
            renderChart();
        }
    }

    void selectYearsPreset(int years) {
        this.activeYearsPreset = years;
        this.activeMonthsPreset = null;
        this.customZoomActive = false;
        updatePresetButtonStyles();
        if (!allPoints.isEmpty()) {
            applyYearsPresetBounds(years);
            syncSlidersToIndices();
            updateControlStates();
            renderChart();
        }
    }

    void selectMaxPreset() {
        this.activeMonthsPreset = null;
        this.activeYearsPreset = null;
        this.customZoomActive = false;
        updatePresetButtonStyles();
        if (!allPoints.isEmpty()) {
            startIndex = 0;
            endIndex = allPoints.size() - 1;
            syncSlidersToIndices();
            updateControlStates();
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
        this.activeMonthsPreset = null;
        this.activeYearsPreset = null;
        this.customZoomActive = clampedStart > 0 || clampedEnd < maxIdx;
        updatePresetButtonStyles();
        syncSlidersToIndices();
        updateControlStates();
        renderChart();
    }

    void zoomIn() {
        handlePlotWheelZoom(0.5, true);
    }

    void zoomOut() {
        handlePlotWheelZoom(0.5, false);
    }

    void panLeft() {
        panBy(-1);
    }

    void panRight() {
        panBy(1);
    }

    void setYFocusEnabled(boolean enabled) {
        this.yFocusEnabled = enabled;
        updateControlStates();
        if (!allPoints.isEmpty()) {
            renderChart();
        }
    }

    boolean isYFocusEnabled() {
        return yFocusEnabled;
    }

    void setEvenPointSpacing(boolean enabled) {
        this.evenPointSpacing = enabled;
        updateControlStates();
        if (!allPoints.isEmpty()) {
            renderChart();
        }
    }

    boolean isEvenPointSpacing() {
        return evenPointSpacing;
    }

    @ClientCallable
    void handlePlotRangeZoom(double startRatio, double endRatio) {
        List<BalanceChartPoint> visible = visiblePointsForTest();
        if (visible.size() < MIN_VISIBLE_POINTS) {
            return;
        }
        double clampedStartRatio = Math.max(0.0, Math.min(1.0, Math.min(startRatio, endRatio)));
        double clampedEndRatio = Math.max(0.0, Math.min(1.0, Math.max(startRatio, endRatio)));
        double leftX = PLOT_LEFT + clampedStartRatio * PLOT_WIDTH;
        double rightX = PLOT_LEFT + clampedEndRatio * PLOT_WIDTH;

        double[] xs = computeXPositions(visible);
        int firstInside = -1;
        int lastInside = -1;
        for (int i = 0; i < xs.length; i++) {
            if (xs[i] >= leftX && xs[i] <= rightX) {
                if (firstInside == -1) {
                    firstInside = i;
                }
                lastInside = i;
            }
        }

        int relStart;
        int relEnd;
        if (firstInside != -1 && lastInside - firstInside >= 1) {
            relStart = firstInside;
            relEnd = lastInside;
        } else {
            int leftFloor = 0;
            for (int i = 0; i < xs.length; i++) {
                if (xs[i] <= leftX) {
                    leftFloor = i;
                }
            }
            int rightCeil = xs.length - 1;
            for (int i = xs.length - 1; i >= 0; i--) {
                if (xs[i] >= rightX) {
                    rightCeil = i;
                }
            }
            relStart = Math.min(leftFloor, rightCeil);
            relEnd = Math.max(leftFloor, rightCeil);
            if (relEnd - relStart < 1) {
                if (relEnd < xs.length - 1) {
                    relEnd++;
                } else if (relStart > 0) {
                    relStart--;
                }
            }
        }
        setRangeIndices(startIndex + relStart, startIndex + relEnd);
    }

    @ClientCallable
    void handlePlotWheelZoom(double anchorRatio, boolean zoomIn) {
        if (allPoints.size() <= MIN_VISIBLE_POINTS) {
            return;
        }
        int maxIdx = allPoints.size() - 1;
        int currentSpan = endIndex - startIndex;
        if (currentSpan <= 0) {
            return;
        }

        List<BalanceChartPoint> visible = visiblePointsForTest();
        double[] xs = computeXPositions(visible);
        double clampedRatio = Math.max(0.0, Math.min(1.0, anchorRatio));
        double targetX = PLOT_LEFT + clampedRatio * PLOT_WIDTH;
        int anchorVisibleIdx = closestPointIndex(xs, targetX);
        double anchorFraction = currentSpan > 0 ? (double) anchorVisibleIdx / currentSpan : 0.5;
        int anchorAbsIdx = startIndex + anchorVisibleIdx;

        int newSpan;
        if (zoomIn) {
            if (currentSpan <= 1) {
                return;
            }
            newSpan = Math.max(1, (int) Math.floor(currentSpan * ZOOM_STEP_FACTOR));
            if (newSpan >= currentSpan) {
                newSpan = currentSpan - 1;
            }
        } else {
            if (startIndex == 0 && endIndex == maxIdx) {
                return;
            }
            newSpan = Math.min(maxIdx, Math.max(currentSpan + 2, (int) Math.ceil(currentSpan / ZOOM_STEP_FACTOR)));
        }

        int newStart = (int) Math.round(anchorAbsIdx - anchorFraction * newSpan);
        newStart = Math.max(0, Math.min(newStart, maxIdx - newSpan));
        int newEnd = Math.min(maxIdx, newStart + newSpan);
        if (newStart == 0 && newEnd == maxIdx) {
            selectMaxPreset();
        } else {
            setRangeIndices(newStart, newEnd);
        }
    }

    private void panBy(int direction) {
        if (allPoints.size() <= 1) {
            return;
        }
        int maxIdx = allPoints.size() - 1;
        int span = endIndex - startIndex;
        if (span >= maxIdx) {
            return;
        }
        int step = Math.max(1, (int) Math.round(span * PAN_STEP_FACTOR)) * direction;
        int newStart = Math.max(0, Math.min(startIndex + step, maxIdx - span));
        int newEnd = newStart + span;
        if (newStart != startIndex || newEnd != endIndex) {
            setRangeIndices(newStart, newEnd);
        }
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

    private HorizontalLayout createHeaderBar() {
        investedLegendItem = createLegendItem(
                COLOR_INVESTED_CAPITAL, getTranslation("finance.chart.legend.invested-capital"), true);
        investedLegendItem.setVisible(false);

        loansLegendItem = createLegendItem(
                COLOR_LOAN, getTranslation("finance.chart.legend.loans"), true);
        loansLegendItem.setVisible(false);

        eventsLegendItem = createLegendItem(
                COLOR_WEALTH_EVENT, getTranslation("finance.chart.legend.events"), false);
        eventsLegendItem.setVisible(false);

        HorizontalLayout legend = new HorizontalLayout(
                createLegendItem(COLOR_TOTAL, getTranslation("finance.view.kpi.net-worth"), true),
                investedLegendItem,
                loansLegendItem,
                eventsLegendItem,
                createLegendItem(COLOR_INVESTMENT, getTranslation("finance.account.type.INVESTMENT"), false),
                createLegendItem(COLOR_SAVINGS, getTranslation("finance.account.type.SAVINGS"), false),
                createLegendItem(COLOR_CHECKING, getTranslation("finance.account.type.CHECKING"), false));
        legend.setDefaultVerticalComponentAlignment(Alignment.CENTER);
        legend.setSpacing(true);
        legend.getStyle().set("flex-wrap", "wrap");

        rangeSummary.addClassNames(LumoUtility.TextColor.SECONDARY, LumoUtility.FontSize.SMALL);
        deltaBadge.getElement().getThemeList().add("badge");
        deltaBadge.getElement().getThemeList().add("pill");

        manageEventsButton = new Button(
                getTranslation("finance.chart.button.events"),
                VaadinIcon.CLIPBOARD_TEXT.create(),
                event -> {
                    if (onManageEventsClick != null) {
                        onManageEventsClick.run();
                    }
                });
        manageEventsButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        manageEventsButton.setTooltipText(getTranslation("finance.chart.events.hint"));
        manageEventsButton.setVisible(false);

        HorizontalLayout header = new HorizontalLayout(legend, manageEventsButton, deltaBadge, rangeSummary);
        header.setWidthFull();
        header.setDefaultVerticalComponentAlignment(Alignment.CENTER);
        header.expand(legend);
        return header;
    }

    private HorizontalLayout createControlsBar() {
        HorizontalLayout presets = new HorizontalLayout();
        presets.setSpacing(false);
        presets.getStyle().set("gap", "var(--lumo-space-xs)").set("flex-wrap", "wrap");

        for (int months : MONTH_PRESETS) {
            Button btn = new Button(getTranslation("finance.chart.range." + months + "m"),
                    event -> selectMonthsPreset(months));
            btn.addThemeVariants(ButtonVariant.LUMO_SMALL);
            monthPresetButtons.put(months, btn);
            presets.add(btn);
        }
        for (int years : YEAR_PRESETS) {
            Button btn = new Button(getTranslation("finance.chart.range." + years + "y"),
                    event -> selectYearsPreset(years));
            btn.addThemeVariants(ButtonVariant.LUMO_SMALL);
            yearPresetButtons.put(years, btn);
            presets.add(btn);
        }
        maxPresetButton.setText(getTranslation("finance.chart.range.max"));
        maxPresetButton.addThemeVariants(ButtonVariant.LUMO_SMALL);
        maxPresetButton.addClickListener(event -> selectMaxPreset());
        presets.add(maxPresetButton);

        yScaleButton = new Button("", event -> setYFocusEnabled(!yFocusEnabled));
        yScaleButton.addThemeVariants(ButtonVariant.LUMO_SMALL);
        yScaleButton.setTooltipText(getTranslation("finance.chart.scale.y-tooltip"));

        xScaleButton = new Button("", event -> setEvenPointSpacing(!evenPointSpacing));
        xScaleButton.addThemeVariants(ButtonVariant.LUMO_SMALL);
        xScaleButton.setTooltipText(getTranslation("finance.chart.scale.x-tooltip"));

        panLeftButton = new Button(VaadinIcon.ANGLE_LEFT.create(), event -> panLeft());
        panLeftButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ICON);
        panLeftButton.setTooltipText(getTranslation("finance.chart.pan.left"));

        panRightButton = new Button(VaadinIcon.ANGLE_RIGHT.create(), event -> panRight());
        panRightButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ICON);
        panRightButton.setTooltipText(getTranslation("finance.chart.pan.right"));

        zoomOutButton = new Button(VaadinIcon.SEARCH_MINUS.create(), event -> zoomOut());
        zoomOutButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ICON);
        zoomOutButton.setTooltipText(getTranslation("finance.chart.zoom.out"));

        zoomInButton = new Button(VaadinIcon.SEARCH_PLUS.create(), event -> zoomIn());
        zoomInButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ICON);
        zoomInButton.setTooltipText(getTranslation("finance.chart.zoom.in") + " – "
                + getTranslation("finance.chart.zoom.hint"));

        HorizontalLayout tools = new HorizontalLayout(
                yScaleButton, xScaleButton, panLeftButton, panRightButton, zoomOutButton, zoomInButton);
        tools.setSpacing(false);
        tools.setDefaultVerticalComponentAlignment(Alignment.CENTER);
        tools.getStyle().set("gap", "var(--lumo-space-xs)").set("flex-wrap", "wrap");

        HorizontalLayout controlsBar = new HorizontalLayout(presets, tools);
        controlsBar.setWidthFull();
        controlsBar.setDefaultVerticalComponentAlignment(Alignment.CENTER);
        controlsBar.setJustifyContentMode(JustifyContentMode.BETWEEN);
        controlsBar.getStyle().set("flex-wrap", "wrap").set("gap", "var(--lumo-space-xs)");

        updatePresetButtonStyles();
        updateControlStates();
        return controlsBar;
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

    private void applyMonthsPresetBounds(int months) {
        endIndex = allPoints.size() - 1;
        LocalDate cutoff = allPoints.get(endIndex).date().minusMonths(months);
        applyCutoffStart(cutoff);
    }

    private void applyYearsPresetBounds(int years) {
        endIndex = allPoints.size() - 1;
        LocalDate cutoff = allPoints.get(endIndex).date().minusYears(years);
        applyCutoffStart(cutoff);
    }

    private void applyCutoffStart(LocalDate cutoff) {
        int found = 0;
        for (int i = 0; i < allPoints.size(); i++) {
            if (!allPoints.get(i).date().isBefore(cutoff)) {
                found = i;
                break;
            }
        }
        startIndex = found;
    }

    private void restoreCustomDateBounds(LocalDate prevStartDate, LocalDate prevEndDate) {
        int newStart = 0;
        for (int i = 0; i < allPoints.size(); i++) {
            if (!allPoints.get(i).date().isBefore(prevStartDate)) {
                newStart = i;
                break;
            }
        }
        int newEnd = allPoints.size() - 1;
        for (int i = allPoints.size() - 1; i >= newStart; i--) {
            if (!allPoints.get(i).date().isAfter(prevEndDate)) {
                newEnd = i;
                break;
            }
        }
        startIndex = Math.max(0, newStart);
        endIndex = Math.max(startIndex, newEnd);
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
        monthPresetButtons.forEach((months, button) -> {
            if (Integer.valueOf(months).equals(activeMonthsPreset)) {
                button.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            } else {
                button.removeThemeVariants(ButtonVariant.LUMO_PRIMARY);
            }
        });
        yearPresetButtons.forEach((years, button) -> {
            if (Integer.valueOf(years).equals(activeYearsPreset)) {
                button.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            } else {
                button.removeThemeVariants(ButtonVariant.LUMO_PRIMARY);
            }
        });
        if (activeMonthsPreset == null && activeYearsPreset == null && !customZoomActive) {
            maxPresetButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        } else {
            maxPresetButton.removeThemeVariants(ButtonVariant.LUMO_PRIMARY);
        }
    }

    private void updateControlStates() {
        if (yScaleButton != null) {
            yScaleButton.setText(getTranslation(
                    yFocusEnabled ? "finance.chart.scale.y-focus" : "finance.chart.scale.y-zero"));
            if (yFocusEnabled) {
                yScaleButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            } else {
                yScaleButton.removeThemeVariants(ButtonVariant.LUMO_PRIMARY);
            }
        }
        if (xScaleButton != null) {
            xScaleButton.setText(getTranslation(
                    evenPointSpacing ? "finance.chart.scale.x-even" : "finance.chart.scale.x-time"));
            if (evenPointSpacing) {
                xScaleButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            } else {
                xScaleButton.removeThemeVariants(ButtonVariant.LUMO_PRIMARY);
            }
        }
        int maxIdx = Math.max(0, allPoints.size() - 1);
        int span = endIndex - startIndex;
        if (zoomInButton != null) {
            zoomInButton.setEnabled(allPoints.size() > MIN_VISIBLE_POINTS && span > 1);
        }
        if (zoomOutButton != null) {
            zoomOutButton.setEnabled(allPoints.size() > 1 && (startIndex > 0 || endIndex < maxIdx));
        }
        if (panLeftButton != null) {
            panLeftButton.setEnabled(allPoints.size() > 1 && startIndex > 0);
        }
        if (panRightButton != null) {
            panRightButton.setEnabled(allPoints.size() > 1 && endIndex < maxIdx);
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
        if (investedLegendItem != null) {
            investedLegendItem.setVisible(hasInvestedCapital(visible));
        }
        if (loansLegendItem != null) {
            loansLegendItem.setVisible(hasLoans(visible));
        }
        if (eventsLegendItem != null) {
            eventsLegendItem.setVisible(hasVisibleEvents(visible));
        }
        chartContainer.getElement().setProperty("innerHTML", lastSvgMarkup);
    }

    @ClientCallable
    void handleChartIntervalDoubleClick(int visibleIndex) {
        if (onIntervalDoubleClick == null) {
            return;
        }
        List<BalanceChartPoint> visible = visiblePointsForTest();
        if (visibleIndex < 0 || visibleIndex >= visible.size()) {
            return;
        }
        BalanceChartPoint target = visible.get(visibleIndex);
        BalanceChartPoint previous = previousPointForVisibleIndex(visibleIndex);
        LocalDate prevDate = previous != null ? previous.date() : null;
        BigDecimal delta = previous != null ? target.total().subtract(previous.total()) : BigDecimal.ZERO;
        onIntervalDoubleClick.accept(new ChartInterval(prevDate, target.date(), delta));
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
        YScale scale = computeYScale(points, yFocusEnabled);
        double minDouble = scale.min().doubleValue();
        double maxDouble = scale.max().doubleValue();
        double[] xs = computeXPositions(points);

        StringBuilder svg = new StringBuilder(8192);
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100%\" height=\"100%\" viewBox=\"0 0 ")
                .append(VIEWBOX_WIDTH).append(' ').append(VIEWBOX_HEIGHT)
                .append("\" preserveAspectRatio=\"xMidYMid meet\" ")
                .append("data-ymin=\"").append(scale.min().toPlainString()).append("\" ")
                .append("data-ymax=\"").append(scale.max().toPlainString()).append("\" ")
                .append("data-xmode=\"").append(evenPointSpacing ? "EVEN" : "TIME").append("\" ")
                .append("style=\"flex:1 1 auto;width:100%;height:100%;display:block;font-family:var(--lumo-font-family);\">");

        boolean showDeltaBand = points.size() > 1;
        double xAxisBottom = showDeltaBand ? DELTA_BOTTOM : PLOT_BOTTOM;

        appendDefs(svg);
        appendYGridAndLabels(svg, scale);
        appendXGridAndLabels(svg, points, xs, xAxisBottom);

        svg.append("<g clip-path=\"url(#mp-plot-clip)\">");
        appendStackedArea(svg, "CHECKING", "url(#mp-grad-checking)", COLOR_CHECKING, points, xs,
                minDouble, maxDouble, p -> BigDecimal.ZERO, BalanceChartPoint::checking);
        appendStackedArea(svg, "SAVINGS", "url(#mp-grad-savings)", COLOR_SAVINGS, points, xs,
                minDouble, maxDouble, BalanceChartPoint::checking, BalanceChartPoint::stackedSavings);
        appendStackedArea(svg, "INVESTMENT", "url(#mp-grad-investment)", COLOR_INVESTMENT, points, xs,
                minDouble, maxDouble, BalanceChartPoint::stackedSavings, BalanceChartPoint::stackedInvestment);
        svg.append("</g>");

        appendAxesLines(svg);
        svg.append("<g clip-path=\"url(#mp-plot-clip)\">");
        if (hasInvestedCapital(points)) {
            appendDashedSeriesLine(svg, "INVESTED_CAPITAL", COLOR_INVESTED_CAPITAL, "6 4",
                    points, xs, minDouble, maxDouble, BalanceChartPoint::investedCapital);
        }
        if (hasLoans(points)) {
            appendDashedSeriesLine(svg, "LOAN", COLOR_LOAN, "4 4",
                    points, xs, minDouble, maxDouble, BalanceChartPoint::loans);
        }
        svg.append("</g>");
        appendTotalLineAndMarkers(svg, points, xs, minDouble, maxDouble);
        if (showDeltaBand) {
            appendDeltaSubchart(svg, points, xs);
        }

        svg.append("</svg>");
        return svg.toString();
    }

    private double[] computeXPositions(List<BalanceChartPoint> points) {
        double[] xs = new double[points.size()];
        if (points.isEmpty()) {
            return xs;
        }
        if (points.size() == 1) {
            xs[0] = PLOT_LEFT + PLOT_WIDTH / 2.0;
            return xs;
        }
        if (evenPointSpacing) {
            int maxIdx = points.size() - 1;
            for (int i = 0; i < points.size(); i++) {
                xs[i] = PLOT_LEFT + ((double) i / maxIdx) * PLOT_WIDTH;
            }
            return xs;
        }
        long minEpoch = points.get(0).date().toEpochDay();
        long maxEpoch = points.get(points.size() - 1).date().toEpochDay();
        for (int i = 0; i < points.size(); i++) {
            xs[i] = xFor(points.get(i).date().toEpochDay(), minEpoch, maxEpoch);
        }
        return xs;
    }

    private static int closestPointIndex(double[] xs, double targetX) {
        int best = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < xs.length; i++) {
            double d = Math.abs(xs[i] - targetX);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    private static void appendDefs(StringBuilder svg) {
        svg.append("<defs>")
                .append(verticalGradient("mp-grad-checking", COLOR_CHECKING, "0.55", "0.18"))
                .append(verticalGradient("mp-grad-savings", COLOR_SAVINGS, "0.55", "0.18"))
                .append(verticalGradient("mp-grad-investment", COLOR_INVESTMENT, "0.60", "0.20"))
                .append("<clipPath id=\"mp-plot-clip\"><rect x=\"").append(fmt(PLOT_LEFT))
                .append("\" y=\"").append(fmt(PLOT_TOP))
                .append("\" width=\"").append(fmt(PLOT_WIDTH))
                .append("\" height=\"").append(fmt(PLOT_HEIGHT))
                .append("\"/></clipPath>")
                .append("</defs>")
                .append("<style>")
                .append(".mp-pt{transition:r 0.12s ease,stroke-width 0.12s ease;cursor:pointer;}")
                .append(".mp-pt:hover{r:7.5px;stroke-width:2.5px;}")
                .append(".mp-seg{cursor:pointer;transition:stroke 0.12s ease;}")
                .append(".mp-seg:hover{stroke:rgba(245,158,11,0.45);}")
                .append(".mp-delta{cursor:pointer;transition:opacity 0.12s ease;}")
                .append(".mp-delta:hover{opacity:1 !important;}")
                .append("</style>");
    }

    private static String verticalGradient(String id, String color, String topOpacity, String bottomOpacity) {
        return "<linearGradient id=\"" + id + "\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\">"
                + "<stop offset=\"0%\" stop-color=\"" + color + "\" stop-opacity=\"" + topOpacity + "\"/>"
                + "<stop offset=\"100%\" stop-color=\"" + color + "\" stop-opacity=\"" + bottomOpacity + "\"/>"
                + "</linearGradient>";
    }

    private void appendYGridAndLabels(StringBuilder svg, YScale scale) {
        double minDouble = scale.min().doubleValue();
        double maxDouble = scale.max().doubleValue();
        for (int i = 0; i <= scale.tickCount(); i++) {
            BigDecimal tickValue = scale.min().add(scale.step().multiply(BigDecimal.valueOf(i)));
            double y = yFor(tickValue.doubleValue(), minDouble, maxDouble);
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

    private void appendXGridAndLabels(StringBuilder svg, List<BalanceChartPoint> points, double[] xs,
            double xAxisBottom) {
        List<Integer> tickIndices = selectTickIndices(points.size(), MAX_X_TICKS);
        for (int idx : tickIndices) {
            double x = xs[idx];
            String label = DateFormatter.format(points.get(idx).date(), locale());
            svg.append("<line x1=\"").append(fmt(x))
                    .append("\" y1=\"").append(fmt(PLOT_TOP))
                    .append("\" x2=\"").append(fmt(x))
                    .append("\" y2=\"").append(fmt(xAxisBottom))
                    .append("\" stroke=\"var(--lumo-contrast-10pct)\" stroke-dasharray=\"3 3\" stroke-width=\"1\"/>");
            svg.append("<line x1=\"").append(fmt(x))
                    .append("\" y1=\"").append(fmt(xAxisBottom))
                    .append("\" x2=\"").append(fmt(x))
                    .append("\" y2=\"").append(fmt(xAxisBottom + 6))
                    .append("\" stroke=\"var(--lumo-contrast-30pct)\" stroke-width=\"1.5\"/>");
            svg.append("<text x=\"").append(fmt(x))
                    .append("\" y=\"").append(fmt(xAxisBottom + 24))
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
            double minDouble,
            double maxDouble,
            Function<BalanceChartPoint, BigDecimal> lowerExtractor,
            Function<BalanceChartPoint, BigDecimal> upperExtractor) {
        if (points.size() == 1) {
            double x = xs[0];
            double yTop = yFor(upperExtractor.apply(points.get(0)).doubleValue(), minDouble, maxDouble);
            double yBottom = yFor(lowerExtractor.apply(points.get(0)).doubleValue(), minDouble, maxDouble);
            svg.append("<line data-series=\"").append(seriesKey)
                    .append("\" x1=\"").append(fmt(x)).append("\" y1=\"").append(fmt(yBottom))
                    .append("\" x2=\"").append(fmt(x)).append("\" y2=\"").append(fmt(yTop))
                    .append("\" stroke=\"").append(stroke).append("\" stroke-width=\"20\"/>");
            return;
        }

        boolean anyUpperVisible = false;
        StringBuilder areaPath = new StringBuilder();
        StringBuilder topEdgePath = new StringBuilder();
        for (int i = 0; i < points.size(); i++) {
            double x = xs[i];
            double upperVal = upperExtractor.apply(points.get(i)).doubleValue();
            if (upperVal > minDouble) {
                anyUpperVisible = true;
            }
            double yTop = yFor(upperVal, minDouble, maxDouble);
            areaPath.append(i == 0 ? "M " : " L ").append(fmt(x)).append(' ').append(fmt(yTop));
            topEdgePath.append(i == 0 ? "M " : " L ").append(fmt(x)).append(' ').append(fmt(yTop));
        }
        for (int i = points.size() - 1; i >= 0; i--) {
            double x = xs[i];
            double yBottom = yFor(lowerExtractor.apply(points.get(i)).doubleValue(), minDouble, maxDouble);
            areaPath.append(" L ").append(fmt(x)).append(' ').append(fmt(yBottom));
        }
        areaPath.append(" Z");

        svg.append("<path data-series=\"").append(seriesKey)
                .append("\" d=\"").append(areaPath)
                .append("\" fill=\"").append(fill).append("\" stroke=\"none\"/>");
        if (anyUpperVisible) {
            svg.append("<path d=\"").append(topEdgePath)
                    .append("\" fill=\"none\" stroke=\"").append(stroke)
                    .append("\" stroke-width=\"1.75\" stroke-linejoin=\"round\"/>");
        }
    }

    private void appendTotalLineAndMarkers(StringBuilder svg, List<BalanceChartPoint> points, double[] xs,
            double minDouble, double maxDouble) {
        if (points.size() > 1) {
            StringBuilder totalPath = new StringBuilder();
            for (int i = 0; i < points.size(); i++) {
                double x = xs[i];
                double y = yFor(points.get(i).total().doubleValue(), minDouble, maxDouble);
                totalPath.append(i == 0 ? "M " : " L ").append(fmt(x)).append(' ').append(fmt(y));
            }
            svg.append("<path data-series=\"TOTAL\" d=\"").append(totalPath)
                    .append("\" fill=\"none\" stroke=\"").append(COLOR_TOTAL)
                    .append("\" stroke-width=\"3\" stroke-linejoin=\"round\" stroke-linecap=\"round\"/>");

            for (int i = 1; i < points.size(); i++) {
                double x1 = xs[i - 1];
                double y1 = yFor(points.get(i - 1).total().doubleValue(), minDouble, maxDouble);
                double x2 = xs[i];
                double y2 = yFor(points.get(i).total().doubleValue(), minDouble, maxDouble);
                BalanceChartPoint fromPt = points.get(i - 1);
                BalanceChartPoint toPt = points.get(i);
                List<WealthEvent> segEvents = eventsForVisibleIndex(i, points);
                svg.append("<line class=\"mp-seg\" data-idx=\"").append(i)
                        .append("\" x1=\"").append(fmt(x1)).append("\" y1=\"").append(fmt(y1))
                        .append("\" x2=\"").append(fmt(x2)).append("\" y2=\"").append(fmt(y2))
                        .append("\" stroke=\"transparent\" stroke-width=\"18\" stroke-linecap=\"round\">")
                        .append("<title>").append(escapeXml(buildSegmentTooltip(fromPt, toPt, segEvents))).append("</title>")
                        .append("</line>");
            }
        } else {
            svg.append("<g data-series=\"TOTAL\"/>");
        }

        String radius = points.size() <= 35 ? "5" : (points.size() <= 70 ? "4" : "3");
        String eventRingRadius = points.size() <= 35 ? "8.5" : "7.5";
        for (int i = 0; i < points.size(); i++) {
            BalanceChartPoint point = points.get(i);
            List<WealthEvent> pointEvents = eventsForVisibleIndex(i, points);
            BalanceChartPoint prevPoint = previousPointForVisibleIndex(i);
            double x = xs[i];
            double y = yFor(point.total().doubleValue(), minDouble, maxDouble);
            svg.append("<circle class=\"mp-pt\" data-idx=\"").append(i)
                    .append("\" cx=\"").append(fmt(x))
                    .append("\" cy=\"").append(fmt(y))
                    .append("\" r=\"").append(radius)
                    .append("\" fill=\"").append(COLOR_TOTAL)
                    .append("\" stroke=\"var(--lumo-base-color)\" stroke-width=\"1.5\">")
                    .append("<title>").append(escapeXml(buildPointTooltip(point, prevPoint, pointEvents))).append("</title>")
                    .append("</circle>");
            if (!pointEvents.isEmpty()) {
                svg.append("<circle class=\"mp-ev\" data-idx=\"").append(i)
                        .append("\" cx=\"").append(fmt(x))
                        .append("\" cy=\"").append(fmt(y))
                        .append("\" r=\"").append(eventRingRadius)
                        .append("\" fill=\"none\" stroke=\"").append(COLOR_WEALTH_EVENT)
                        .append("\" stroke-width=\"2\" pointer-events=\"none\"/>");
            }
        }
    }

    private static boolean hasInvestedCapital(List<BalanceChartPoint> points) {
        return points.stream().anyMatch(point -> point.investedCapital().signum() > 0);
    }

    private static boolean hasLoans(List<BalanceChartPoint> points) {
        return points.stream().anyMatch(point -> point.loans().signum() > 0);
    }

    private void appendDashedSeriesLine(
            StringBuilder svg,
            String seriesKey,
            String color,
            String dashArray,
            List<BalanceChartPoint> points,
            double[] xs,
            double minDouble,
            double maxDouble,
            Function<BalanceChartPoint, BigDecimal> extractor) {
        int i = 0;
        while (i < points.size()) {
            if (extractor.apply(points.get(i)).signum() <= 0) {
                i++;
                continue;
            }
            int runStart = i;
            while (i + 1 < points.size() && extractor.apply(points.get(i + 1)).signum() > 0) {
                i++;
            }
            int runEnd = i;
            if (runStart == runEnd) {
                double x = xs[runStart];
                double y = yFor(extractor.apply(points.get(runStart)).doubleValue(), minDouble, maxDouble);
                svg.append("<circle data-series=\"").append(seriesKey).append("\" cx=\"").append(fmt(x))
                        .append("\" cy=\"").append(fmt(y))
                        .append("\" r=\"4.5\" fill=\"").append(color)
                        .append("\" stroke=\"var(--lumo-base-color)\" stroke-width=\"1.5\"/>");
            } else {
                StringBuilder path = new StringBuilder();
                for (int k = runStart; k <= runEnd; k++) {
                    double x = xs[k];
                    double y = yFor(extractor.apply(points.get(k)).doubleValue(), minDouble, maxDouble);
                    path.append(k == runStart ? "M " : " L ").append(fmt(x)).append(' ').append(fmt(y));
                }
                svg.append("<path data-series=\"").append(seriesKey).append("\" d=\"").append(path)
                        .append("\" fill=\"none\" stroke=\"").append(color)
                        .append("\" stroke-width=\"2.5\" stroke-dasharray=\"").append(dashArray)
                        .append("\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>");
            }
            i++;
        }
    }

    private void appendDeltaSubchart(StringBuilder svg, List<BalanceChartPoint> points, double[] xs) {
        BigDecimal minDelta = BigDecimal.ZERO;
        BigDecimal maxDelta = BigDecimal.ZERO;
        BigDecimal[] deltas = new BigDecimal[points.size()];
        for (int i = 1; i < points.size(); i++) {
            deltas[i] = points.get(i).total().subtract(points.get(i - 1).total());
            minDelta = minDelta.min(deltas[i]);
            maxDelta = maxDelta.max(deltas[i]);
        }
        DeltaScale scale = computeDeltaScale(minDelta, maxDelta);
        double dMin = scale.min().doubleValue();
        double dMax = scale.max().doubleValue();
        double yZero = deltaYFor(0.0, dMin, dMax);

        svg.append("<line x1=\"").append(fmt(PLOT_LEFT)).append("\" y1=\"").append(fmt(DELTA_TOP))
                .append("\" x2=\"").append(fmt(PLOT_LEFT)).append("\" y2=\"").append(fmt(DELTA_BOTTOM))
                .append("\" stroke=\"var(--lumo-contrast-30pct)\" stroke-width=\"1.5\"/>");
        svg.append("<line x1=\"").append(fmt(PLOT_LEFT)).append("\" y1=\"").append(fmt(DELTA_BOTTOM))
                .append("\" x2=\"").append(fmt(PLOT_RIGHT)).append("\" y2=\"").append(fmt(DELTA_BOTTOM))
                .append("\" stroke=\"var(--lumo-contrast-30pct)\" stroke-width=\"1.5\"/>");

        BigDecimal[] yLabels = (dMin < 0 && dMax > 0)
                ? new BigDecimal[] { scale.max(), BigDecimal.ZERO, scale.min() }
                : new BigDecimal[] { scale.max(), scale.min() };
        for (BigDecimal tickVal : yLabels) {
            double y = deltaYFor(tickVal.doubleValue(), dMin, dMax);
            boolean isZero = tickVal.signum() == 0;
            svg.append("<line x1=\"").append(fmt(PLOT_LEFT)).append("\" y1=\"").append(fmt(y))
                    .append("\" x2=\"").append(fmt(PLOT_RIGHT)).append("\" y2=\"").append(fmt(y))
                    .append("\" stroke=\"").append(isZero ? "var(--lumo-contrast-30pct)" : "var(--lumo-contrast-10pct)")
                    .append(isZero ? "\" stroke-width=\"1.2\"/>" : "\" stroke-dasharray=\"4 4\" stroke-width=\"1\"/>");
            svg.append("<text x=\"").append(fmt(PLOT_LEFT - 12)).append("\" y=\"").append(fmt(y + 4))
                    .append("\" text-anchor=\"end\" fill=\"var(--lumo-secondary-text-color)\" font-size=\"11\" font-weight=\"500\">")
                    .append(escapeXml(formatAxisMoney(tickVal)))
                    .append("</text>");
        }
        svg.append("<text x=\"").append(fmt(PLOT_LEFT + 6)).append("\" y=\"").append(fmt(DELTA_TOP + 12))
                .append("\" fill=\"var(--lumo-secondary-text-color)\" font-size=\"11\" font-weight=\"600\">")
                .append(escapeXml(getTranslation("finance.chart.delta.axis-label")))
                .append("</text>");

        double minDx = PLOT_WIDTH;
        for (int i = 1; i < xs.length; i++) {
            double dx = xs[i] - xs[i - 1];
            if (dx > 0.5 && dx < minDx) {
                minDx = dx;
            }
        }
        double barW = Math.max(3.0, Math.min(24.0, minDx * 0.65));

        svg.append("<g data-series=\"DELTA_BARS\">");
        for (int i = 1; i < points.size(); i++) {
            double val = deltas[i].doubleValue();
            double yVal = deltaYFor(val, dMin, dMax);
            double rectY = Math.min(yZero, yVal);
            double rectH = Math.abs(yVal - yZero);
            if (deltas[i].signum() != 0 && rectH < 2.5) {
                rectH = 2.5;
                if (val < 0) {
                    rectY = Math.min(DELTA_BOTTOM - rectH, yZero);
                } else {
                    rectY = Math.max(DELTA_TOP, yZero - rectH);
                }
            } else if (rectH < 1.5) {
                rectH = 1.5;
                rectY = yZero - 0.75;
            }
            double barX = Math.max(PLOT_LEFT + 1.0, Math.min(PLOT_RIGHT - barW, xs[i] - barW / 2.0));
            List<WealthEvent> segEvents = eventsForVisibleIndex(i, points);
            boolean hasEvents = !segEvents.isEmpty();
            String fill = val >= 0 ? COLOR_DELTA_POS : COLOR_DELTA_NEG;
            svg.append("<rect class=\"mp-delta\" data-idx=\"").append(i)
                    .append("\" x=\"").append(fmt(barX))
                    .append("\" y=\"").append(fmt(rectY))
                    .append("\" width=\"").append(fmt(barW))
                    .append("\" height=\"").append(fmt(rectH))
                    .append("\" rx=\"1.5\" fill=\"").append(fill)
                    .append("\" opacity=\"").append(hasEvents ? "0.92" : "0.70").append("\"");
            if (hasEvents) {
                svg.append(" stroke=\"").append(COLOR_WEALTH_EVENT).append("\" stroke-width=\"1.8\"");
            }
            svg.append("><title>").append(escapeXml(buildSegmentTooltip(points.get(i - 1), points.get(i), segEvents)))
                    .append("</title></rect>");
        }
        svg.append("</g>");
    }

    private static DeltaScale computeDeltaScale(BigDecimal minDelta, BigDecimal maxDelta) {
        double rawMin = Math.min(0.0, minDelta.doubleValue());
        double rawMax = Math.max(0.0, maxDelta.doubleValue());
        double span = rawMax - rawMin;
        if (span <= 0.0) {
            return new DeltaScale(new BigDecimal("-1000"), new BigDecimal("1000"));
        }
        BigDecimal step = niceStep(span / 2.5);
        double stepVal = step.doubleValue();
        BigDecimal niceMin = rawMin < 0.0
                ? step.multiply(BigDecimal.valueOf((long) Math.floor((rawMin * 1.08) / stepVal)))
                : BigDecimal.ZERO;
        BigDecimal niceMax = rawMax > 0.0
                ? step.multiply(BigDecimal.valueOf((long) Math.ceil((rawMax * 1.08) / stepVal)))
                : BigDecimal.ZERO;
        return new DeltaScale(niceMin, niceMax);
    }

    private record DeltaScale(BigDecimal min, BigDecimal max) {
    }

    private static double deltaYFor(double value, double minDouble, double maxDouble) {
        if (maxDouble <= minDouble) {
            return DELTA_BOTTOM;
        }
        double clamped = Math.max(minDouble, Math.min(value, maxDouble));
        return DELTA_BOTTOM - ((clamped - minDouble) / (maxDouble - minDouble)) * DELTA_HEIGHT;
    }

    private String buildPointTooltip(
            BalanceChartPoint point, BalanceChartPoint previousPoint, List<WealthEvent> pointEvents) {
        Locale loc = locale();
        String base = DateFormatter.format(point.date(), loc) + "\n"
                + getTranslation("finance.view.kpi.net-worth") + ": " + MoneyFormatter.format(point.total(), loc) + "\n"
                + getTranslation("finance.account.type.INVESTMENT") + ": " + MoneyFormatter.format(point.investment(), loc) + "\n"
                + getTranslation("finance.account.type.SAVINGS") + ": " + MoneyFormatter.format(point.savings(), loc) + "\n"
                + getTranslation("finance.account.type.CHECKING") + ": " + MoneyFormatter.format(point.checking(), loc);
        if (point.loans().signum() > 0) {
            base += "\n" + getTranslation("finance.chart.legend.loans") + ": -"
                    + MoneyFormatter.format(point.loans(), loc);
        }
        if (point.investedCapital().signum() > 0) {
            base += "\n" + getTranslation("finance.chart.legend.invested-capital") + ": "
                    + MoneyFormatter.format(point.investedCapital(), loc);
        }
        if (!pointEvents.isEmpty()) {
            String header = previousPoint != null
                    ? getTranslation("finance.chart.tooltip.events-since",
                            DateFormatter.format(previousPoint.date(), loc))
                    : getTranslation("finance.chart.tooltip.events");
            base += "\n" + header + formatEventsLines(pointEvents, loc);
        }
        return base;
    }

    private String buildSegmentTooltip(
            BalanceChartPoint fromPoint, BalanceChartPoint toPoint, List<WealthEvent> segEvents) {
        Locale loc = locale();
        BigDecimal delta = toPoint.total().subtract(fromPoint.total());
        String sign = delta.signum() > 0 ? "+" : "";
        String text = DateFormatter.format(fromPoint.date(), loc) + " → " + DateFormatter.format(toPoint.date(), loc)
                + ": " + sign + MoneyFormatter.format(delta, loc);
        if (!segEvents.isEmpty()) {
            text += formatEventsLines(segEvents, loc);
        }
        return text;
    }

    private static String formatEventsLines(List<WealthEvent> events, Locale loc) {
        StringBuilder sb = new StringBuilder();
        for (WealthEvent ev : events) {
            BigDecimal amt = ev.getAmount();
            String sign = amt != null && amt.signum() > 0 ? "+" : "";
            sb.append("\n• ")
                    .append(amt != null ? sign + MoneyFormatter.format(amt, loc) : "-")
                    .append(" · ").append(ev.getDescription());
        }
        return sb.toString();
    }

    private BalanceChartPoint previousPointForVisibleIndex(int visibleIndex) {
        int absolutePrevIdx = startIndex + visibleIndex - 1;
        if (absolutePrevIdx >= 0 && absolutePrevIdx < allPoints.size()) {
            return allPoints.get(absolutePrevIdx);
        }
        return null;
    }

    private List<WealthEvent> eventsForVisibleIndex(int visibleIndex, List<BalanceChartPoint> visible) {
        if (wealthEvents.isEmpty() || visibleIndex < 0 || visibleIndex >= visible.size()) {
            return List.of();
        }
        BalanceChartPoint prev = previousPointForVisibleIndex(visibleIndex);
        LocalDate prevDate = prev != null ? prev.date() : null;
        return eventsForInterval(prevDate, visible.get(visibleIndex).date(), wealthEvents);
    }

    static List<WealthEvent> eventsForInterval(
            LocalDate prevExclusive, LocalDate targetInclusive, List<WealthEvent> allEvents) {
        if (allEvents == null || allEvents.isEmpty() || targetInclusive == null) {
            return List.of();
        }
        return allEvents.stream()
                .filter(ev -> WealthEventDialog.isDateInInterval(ev.getEventDate(), prevExclusive, targetInclusive))
                .toList();
    }

    private boolean hasVisibleEvents(List<BalanceChartPoint> visible) {
        if (wealthEvents.isEmpty() || visible.isEmpty()) {
            return false;
        }
        for (int i = 0; i < visible.size(); i++) {
            if (!eventsForVisibleIndex(i, visible).isEmpty()) {
                return true;
            }
        }
        return false;
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

    static YScale computeYScale(List<BalanceChartPoint> points, boolean focusY) {
        BigDecimal max = BigDecimal.ZERO;
        BigDecimal min = null;
        boolean includeInvested = hasInvestedCapital(points);
        for (BalanceChartPoint point : points) {
            BigDecimal hi = point.total().max(point.stackedInvestment());
            BigDecimal lo = point.total();
            if (includeInvested && point.investedCapital().signum() > 0) {
                hi = hi.max(point.investedCapital());
                lo = lo.min(point.investedCapital());
            }
            max = max.max(hi);
            min = min == null ? lo : min.min(lo);
        }
        if (max.signum() <= 0) {
            return new YScale(BigDecimal.ZERO, new BigDecimal("250"), 4, new BigDecimal("1000"));
        }

        if (!focusY || min == null) {
            return selectTightestYScale(BigDecimal.ZERO, max.doubleValue() * 1.03);
        }

        double rawMin = min.doubleValue();
        double rawMax = max.doubleValue();
        double rawSpan = rawMax - rawMin;
        if (rawSpan <= 0.0) {
            rawSpan = Math.max(1000.0, rawMax * 0.1);
        }
        double pad = Math.max(100.0, rawSpan * 0.05);
        double targetMin = rawMin >= 0.0 ? Math.max(0.0, rawMin - pad) : rawMin - pad;
        double targetMax = rawMax + pad;
        return selectTightestFocusedYScale(targetMin, targetMax, rawMin >= 0.0);
    }

    private static YScale selectTightestYScale(BigDecimal fixedMin, double targetMax) {
        YScale best = null;
        for (int candidateTicks = MIN_Y_TICKS; candidateTicks <= MAX_Y_TICKS; candidateTicks++) {
            BigDecimal step = niceStep(targetMax / candidateTicks);
            int actualTicks = Math.max(MIN_Y_TICKS, (int) Math.ceil(targetMax / step.doubleValue()));
            BigDecimal candidateMax = fixedMin.add(step.multiply(BigDecimal.valueOf(actualTicks)));
            if (isBetterYScale(best, candidateMax, actualTicks)) {
                best = new YScale(fixedMin, step, actualTicks, candidateMax);
            }
        }
        return best;
    }

    private static YScale selectTightestFocusedYScale(double targetMin, double targetMax, boolean clampMinZero) {
        YScale best = null;
        for (int candidateTicks = MIN_Y_TICKS; candidateTicks <= MAX_Y_TICKS; candidateTicks++) {
            BigDecimal step = niceStep((targetMax - targetMin) / candidateTicks);
            double stepVal = step.doubleValue();
            BigDecimal niceMin = step.multiply(BigDecimal.valueOf((long) Math.floor(targetMin / stepVal)));
            if (clampMinZero && niceMin.signum() < 0) {
                niceMin = BigDecimal.ZERO;
            }
            int actualTicks = Math.max(MIN_Y_TICKS, (int) Math.ceil((targetMax - niceMin.doubleValue()) / stepVal));
            BigDecimal niceMax = niceMin.add(step.multiply(BigDecimal.valueOf(actualTicks)));
            BigDecimal span = niceMax.subtract(niceMin);
            if (best == null) {
                best = new YScale(niceMin, step, actualTicks, niceMax);
                continue;
            }
            int spanCmp = span.compareTo(best.max().subtract(best.min()));
            if (spanCmp < 0 || (spanCmp == 0 && Math.abs(actualTicks - 5.5) < Math.abs(best.tickCount() - 5.5))) {
                best = new YScale(niceMin, step, actualTicks, niceMax);
            }
        }
        return best;
    }

    private static boolean isBetterYScale(YScale currentBest, BigDecimal candidateMax, int actualTicks) {
        if (currentBest == null) {
            return true;
        }
        int maxCmp = candidateMax.compareTo(currentBest.max());
        if (maxCmp != 0) {
            return maxCmp < 0;
        }
        return Math.abs(actualTicks - 5.5) < Math.abs(currentBest.tickCount() - 5.5);
    }

    private static BigDecimal niceStep(double rawStep) {
        if (rawStep <= 0.0) {
            return BigDecimal.ONE;
        }
        double magnitude = Math.pow(10, Math.floor(Math.log10(rawStep)));
        double normalized = rawStep / magnitude;
        double chosenStep = 10.0 * magnitude;
        for (double candidate : NICE_STEPS) {
            if (normalized <= candidate) {
                chosenStep = candidate * magnitude;
                break;
            }
        }
        return BigDecimal.valueOf(Math.max(1L, Math.round(chosenStep)));
    }

    record YScale(BigDecimal min, BigDecimal step, int tickCount, BigDecimal max) {
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

    private static double yFor(double value, double minDouble, double maxDouble) {
        if (maxDouble <= minDouble) {
            return PLOT_BOTTOM;
        }
        double clamped = Math.max(minDouble, Math.min(value, maxDouble));
        return PLOT_BOTTOM - ((clamped - minDouble) / (maxDouble - minDouble)) * PLOT_HEIGHT;
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
