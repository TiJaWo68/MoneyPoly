package de.in.mp.ui;

/**
 * CSS hooks for responsive behavior that cannot be expressed with Lumo utility
 * classes alone.
 */
public final class ResponsiveStyles {

    /** Application stylesheet, resolved relative to {@code META-INF/resources}. */
    public static final String STYLESHEET = "styles/responsive.css";

    /**
     * Hides a component on short viewports (e.g. smartphones in landscape
     * orientation) so that data grids get the available vertical space.
     */
    public static final String HIDE_ON_SHORT_SCREEN = "mp-hide-on-short-screen";

    /** Root container class for compact mobile padding on the dashboard. */
    public static final String DASHBOARD_VIEW = "mp-dashboard-view";

    /** Grid class reducing horizontal cell padding on narrow smartphone screens. */
    public static final String DASHBOARD_GRID = "mp-dashboard-grid";

    /** Performance cell class stacking monetary gain and percent on narrow screens. */
    public static final String DASHBOARD_PERF_CELL = "mp-dashboard-perf";

    /** Section header sum label allowing flexible shrinking on mobile screens. */
    public static final String DASHBOARD_HEADER_SUM = "mp-dashboard-header-sum";

    /** Section header progress indicator allowing compact sizing on mobile screens. */
    public static final String DASHBOARD_HEADER_PROGRESS = "mp-dashboard-header-progress";

    /** Viewport width threshold below which compact smartphone dashboard columns apply. */
    public static final int MOBILE_BREAKPOINT_PX = 700;

    /**
     * Media query rules for mobile landscape height limits and narrow smartphone
     * dashboard layouts.
     */
    public static final String CSS_RULES = """
            @media (orientation: landscape) and (max-height: 500px) {
                .""" + HIDE_ON_SHORT_SCREEN + """
                 {
                    display: none !important;
                }
            }
            @media (max-width: 699px) {
                .""" + DASHBOARD_VIEW + """
                 {
                    padding: var(--lumo-space-xs) var(--lumo-space-s) !important;
                }
                .""" + DASHBOARD_GRID + """
                ::part(cell),
                .""" + DASHBOARD_GRID + """
                ::part(header-cell) {
                    padding-inline: var(--lumo-space-xs);
                }
                .""" + DASHBOARD_GRID + """
                ::part(first-column-cell) {
                    padding-inline-start: var(--lumo-space-xs);
                }
                .""" + DASHBOARD_GRID + """
                ::part(last-column-cell) {
                    padding-inline-end: var(--lumo-space-xs);
                }
                .""" + DASHBOARD_PERF_CELL + """
                 {
                    display: inline-flex;
                    flex-direction: column;
                    align-items: flex-end;
                    line-height: 1.15;
                }
                .""" + DASHBOARD_HEADER_PROGRESS + """
                 {
                    width: auto !important;
                    min-width: 0 !important;
                    padding-right: var(--lumo-space-xs) !important;
                }
                .""" + DASHBOARD_HEADER_PROGRESS + """
                 vaadin-progress-bar {
                    width: 44px !important;
                    min-width: 44px !important;
                }
                .""" + DASHBOARD_HEADER_SUM + """
                 {
                    min-width: 0 !important;
                    padding-right: var(--lumo-space-s) !important;
                }
            }
            """;

    private ResponsiveStyles() {
    }
}

