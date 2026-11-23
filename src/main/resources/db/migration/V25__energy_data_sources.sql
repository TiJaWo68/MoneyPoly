CREATE TABLE energy_data_source (
    id UUID PRIMARY KEY,
    house_id UUID NOT NULL REFERENCES energy_house(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    provider VARCHAR(40) NOT NULL,
    base_url VARCHAR(255) NOT NULL,
    username VARCHAR(100),
    secret_encrypted TEXT,
    trust_self_signed BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    auto_sync_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_synced_at TIMESTAMP WITH TIME ZONE,
    last_sync_status VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_energy_data_source_house ON energy_data_source (house_id);

ALTER TABLE meter
    ADD COLUMN data_source_id UUID REFERENCES energy_data_source(id) ON DELETE SET NULL,
    ADD COLUMN source_metric_key VARCHAR(100),
    ADD COLUMN source_offset NUMERIC(12, 3) NOT NULL DEFAULT 0,
    ADD COLUMN last_remote_id BIGINT;
