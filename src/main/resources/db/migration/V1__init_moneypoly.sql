-- ============================================================================
-- TEIL 1: ENUMS & BASIS-TYPEN
-- ============================================================================
CREATE TYPE account_type AS ENUM ('CHECKING', 'SAVINGS', 'BROKERAGE');
CREATE TYPE owner_type AS ENUM ('HUSBAND', 'WIFE', 'JOINT');
CREATE TYPE flow_type AS ENUM (
    'INCOME',
    'EXPENSE',
    'EXPENSE_REIMBURSEMENT',
    'INTERNAL_TRANSFER',
    'SAVINGS_TRANSIT',
    'LOAN_DISBURSEMENT',
    'LOAN_REPAYMENT'
);

CREATE TYPE meter_type AS ENUM (
    'GRID_IMPORT',      -- Strom Netzbezug (1.8.0)
    'GRID_EXPORT',      -- Strom Einspeisung (2.8.0)
    'PV_GENERATION',    -- PV Wechselrichter Erzeugung
    'WALLBOX_COMPANY',  -- Wallbox Dienstwagen
    'WALLBOX_PRIVATE',  -- Wallbox Privat
    'GAS',              -- Gaszähler (m3)
    'WATER'             -- Wasserzähler (m3)
);

-- ============================================================================
-- TEIL 2: MODUL FINANZEN & FAMILIEN-DARLEHEN
-- ============================================================================

CREATE TABLE account (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(80) NOT NULL,
    iban VARCHAR(34) UNIQUE,
    institution VARCHAR(50) NOT NULL,
    type account_type NOT NULL,
    owner owner_type NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE category (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(60) NOT NULL UNIQUE
);

CREATE TABLE family_member (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(50) NOT NULL UNIQUE,
    role VARCHAR(30) NOT NULL DEFAULT 'BORROWER', -- z.B. 'PARENT', 'BORROWER'
    login_email VARCHAR(120) UNIQUE               -- Vorbereitet für späteren Eigen-Login
);

CREATE TABLE loan_contract (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    borrower_id UUID NOT NULL REFERENCES family_member(id),
    designation VARCHAR(80) NOT NULL,             -- z.B. 'Auto', 'Haus', 'PV-Anlage'
    original_amount NUMERIC(12, 2) NOT NULL,
    initial_repaid NUMERIC(12, 2) NOT NULL DEFAULT 0.00,
    start_date DATE NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE bank_transaction (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id UUID NOT NULL REFERENCES account(id),
    booking_date DATE NOT NULL,
    valuta_date DATE,
    amount NUMERIC(12, 2) NOT NULL,
    counterparty_name VARCHAR(255),
    counterparty_iban VARCHAR(34),
    purpose TEXT,
    raw_hash VARCHAR(64) NOT NULL UNIQUE,
    needs_review BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tx_account_date ON bank_transaction(account_id, booking_date);
CREATE INDEX idx_tx_review ON bank_transaction(needs_review) WHERE needs_review = TRUE;

CREATE TABLE transaction_allocation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES bank_transaction(id) ON DELETE CASCADE,
    allocated_amount NUMERIC(12, 2) NOT NULL,
    flow flow_type NOT NULL,
    category_id UUID REFERENCES category(id),
    loan_id UUID REFERENCES loan_contract(id),
    peer_transaction_id UUID REFERENCES bank_transaction(id),
    notes VARCHAR(255)
);

CREATE TABLE classification_rule (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    priority INT NOT NULL DEFAULT 100,
    match_iban VARCHAR(34),
    match_counterparty_regex VARCHAR(255),
    match_purpose_regex VARCHAR(255),
    target_flow flow_type NOT NULL,
    target_category_id UUID REFERENCES category(id),
    target_loan_id UUID REFERENCES loan_contract(id),
    flag_for_review BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE account_snapshot (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id UUID NOT NULL REFERENCES account(id),
    snapshot_date DATE NOT NULL,
    balance NUMERIC(12, 2) NOT NULL,
    UNIQUE (account_id, snapshot_date)
);

-- ============================================================================
-- TEIL 3: MODUL ENERGIE, ZÄHLERSTÄNDE, PV & AMORTISATION
-- ============================================================================

-- Investitionsobjekte (z.B. PV-Anlage Dach, PV-Garage, Wallbox)
CREATE TABLE energy_asset (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(80) NOT NULL,
    installation_date DATE NOT NULL,
    investment_cost NUMERIC(12, 2) NOT NULL,      -- Anschaffungskosten netto/brutto
    initial_amortized NUMERIC(12, 2) NOT NULL DEFAULT 0.00, -- Vor App-Start bereits eingespielt
    notes VARCHAR(255)
);

-- Physische oder virtuelle Zähler
CREATE TABLE meter (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(80) NOT NULL,
    serial_number VARCHAR(50),
    type meter_type NOT NULL,
    unit VARCHAR(10) NOT NULL DEFAULT 'kWh',      -- 'kWh' oder 'm3'
    conversion_factor NUMERIC(8, 4) NOT NULL DEFAULT 1.0000, -- Für Gas: Zustandszahl * Brennwert
    asset_id UUID REFERENCES energy_asset(id),    -- Optionale Zuordnung zu PV/Wallbox
    installed_at DATE,
    replaced_at DATE,                             -- Unterstützt Zählerwechsel!
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

-- Ablesewerte (Zählerstände)
CREATE TABLE meter_reading (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    meter_id UUID NOT NULL REFERENCES meter(id),
    reading_date DATE NOT NULL,
    reading_value NUMERIC(12, 3) NOT NULL,        -- Zählerstand absolut
    consumption_delta NUMERIC(12, 3),             -- Automatisch berechnet zum Vorgängerwert
    notes VARCHAR(255),
    UNIQUE (meter_id, reading_date)
);

-- Historisierte Preise & Vergütungssätze (Strom, Gas, Wasser, EEG, Arbeitgeber-Laden)
CREATE TABLE energy_tariff (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(80) NOT NULL,                    -- z.B. 'Stromtarif 2026', 'AG-Erstattung Dienstwagen'
    for_meter_type meter_type NOT NULL,
    valid_from DATE NOT NULL,
    valid_to DATE,                                -- NULL = aktuell gültig
    unit_price_eur NUMERIC(8, 4) NOT NULL,        -- Preis pro kWh oder m3 in Euro (z.B. 0.3250)
    monthly_base_fee_eur NUMERIC(8, 2) NOT NULL DEFAULT 0.00
);

-- Abrechnungen / Erstattungen für Dienstwagen-Laden beim Arbeitgeber
CREATE TABLE ev_charging_reimbursement (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    billing_month DATE NOT NULL,                  -- Erster Tag des Abrechnungsmonats
    charged_kwh NUMERIC(10, 2) NOT NULL,
    reimbursed_amount_eur NUMERIC(10, 2) NOT NULL,
    bank_transaction_id UUID REFERENCES bank_transaction(id), -- Verknüpfung mit Geldeingang
    asset_id UUID REFERENCES energy_asset(id)     -- Zurechnung zur Wallbox/PV-Amortisation
);
