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

    /**
     * Media query rule hiding elements marked with {@value #HIDE_ON_SHORT_SCREEN}
     * on landscape mobile screens with less than 500px viewport height.
     */
    public static final String CSS_RULES = """
            @media (orientation: landscape) and (max-height: 500px) {
                .""" + HIDE_ON_SHORT_SCREEN + """
                 {
                    display: none !important;
                }
            }
            """;

    private ResponsiveStyles() {
    }
}

