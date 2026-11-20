-- ============================================================================
-- PERSONAL WORK TIME TRACKING (TEACHER PART-TIME / OVERTIME MONITORING)
-- ============================================================================

CREATE TABLE work_time_profile (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    app_user_id UUID NOT NULL UNIQUE REFERENCES app_user(id) ON DELETE CASCADE,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    full_time_weekly_hours NUMERIC(5, 2) NOT NULL DEFAULT 40.00,
    full_time_teaching_units NUMERIC(5, 2) NOT NULL DEFAULT 27.00,
    contract_teaching_units NUMERIC(5, 2) NOT NULL DEFAULT 20.00,
    work_days_per_week INTEGER NOT NULL DEFAULT 5,
    daily_warning_hours NUMERIC(5, 2) NOT NULL DEFAULT 10.00,
    min_rest_hours NUMERIC(5, 2) NOT NULL DEFAULT 11.00,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE work_time_entry (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id UUID NOT NULL REFERENCES work_time_profile(id) ON DELETE CASCADE,
    work_date DATE NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    category VARCHAR(30) NOT NULL DEFAULT 'SCHOOL',
    counts_as_work BOOLEAN NOT NULL DEFAULT TRUE,
    note VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_work_time_entry_profile_date ON work_time_entry(profile_id, work_date, start_time);
