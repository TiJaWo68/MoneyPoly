-- ============================================================================
-- ENERGY & METERS: VARCHAR ENUM MAPPING, METER OVERFLOW & DISPLAY ORDER
-- ============================================================================
-- 1. Convert PostgreSQL named enum meter_type on meter and energy_tariff to
--    VARCHAR(30) with CHECK constraints so JPA @Enumerated(EnumType.STRING)
--    works seamlessly (see V3 and V8).
-- 2. Add overflow_threshold (e.g. 100000 for 5-digit mechanical sub-meters)
--    and display_order to meter for spreadsheet-style column ordering.

ALTER TABLE meter ALTER COLUMN type TYPE VARCHAR(30)
    USING type::text;

ALTER TABLE meter ADD CONSTRAINT chk_meter_type
    CHECK (type IN (
        'GRID_IMPORT',
        'GRID_EXPORT',
        'PV_GENERATION',
        'WALLBOX_COMPANY',
        'WALLBOX_PRIVATE',
        'GAS',
        'WATER'
    ));

ALTER TABLE meter
    ADD COLUMN overflow_threshold NUMERIC(12, 3),
    ADD COLUMN display_order INT NOT NULL DEFAULT 100;

ALTER TABLE energy_tariff ALTER COLUMN for_meter_type TYPE VARCHAR(30)
    USING for_meter_type::text;

ALTER TABLE energy_tariff ADD CONSTRAINT chk_energy_tariff_meter_type
    CHECK (for_meter_type IN (
        'GRID_IMPORT',
        'GRID_EXPORT',
        'PV_GENERATION',
        'WALLBOX_COMPANY',
        'WALLBOX_PRIVATE',
        'GAS',
        'WATER'
    ));

CREATE INDEX idx_meter_reading_date ON meter_reading(reading_date, meter_id);

