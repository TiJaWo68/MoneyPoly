package de.in.mp.ui.dashboard;

/**
 * Sortable columns shared by all dashboard tables. Each table maps the
 * columns to its own values (e.g. balance or remaining loan amount), but the
 * active sorting is kept in sync across all tables.
 */
enum DashboardSortColumn {

    NAME,
    AMOUNT,
    SHARE;

    String key() {
        return name();
    }
}

