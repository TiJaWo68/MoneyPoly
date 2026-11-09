-- ============================================================================
-- FINANCE: BANK DIRECTORY CACHE FOR IBAN-BASED BANK NAME RESOLUTION
-- ============================================================================
-- Caches bank master data (BLZ -> name/BIC) resolved from OpenIBAN at runtime.
-- The cache avoids repeated external lookups and works offline after the first
-- lookup for a given bank code.

CREATE TABLE bank (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    blz VARCHAR(8) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    bic VARCHAR(11),
    zip VARCHAR(10),
    city VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
