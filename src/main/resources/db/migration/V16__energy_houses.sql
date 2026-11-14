-- ============================================================================
-- ENERGY HOUSES: MULTI-HOUSE SUPPORT & USER-SCOPED HOUSE ACCESS (#36)
-- ============================================================================

CREATE TABLE energy_house (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(80) NOT NULL UNIQUE,
    display_order INT NOT NULL DEFAULT 100,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    notes VARCHAR(255)
);

CREATE TABLE app_user_energy_house (
    app_user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    house_id UUID NOT NULL REFERENCES energy_house(id) ON DELETE CASCADE,
    access_role VARCHAR(20) NOT NULL DEFAULT 'EDITOR'
        CHECK (access_role IN ('VIEWER', 'EDITOR')),
    PRIMARY KEY (app_user_id, house_id)
);

INSERT INTO energy_house (name, display_order, is_active)
VALUES ('Haus 1', 1, TRUE);

ALTER TABLE energy_asset ADD COLUMN house_id UUID;
UPDATE energy_asset
SET house_id = (SELECT id FROM energy_house ORDER BY display_order, name LIMIT 1);
ALTER TABLE energy_asset ALTER COLUMN house_id SET NOT NULL;
ALTER TABLE energy_asset ADD CONSTRAINT fk_energy_asset_house
    FOREIGN KEY (house_id) REFERENCES energy_house(id);
CREATE INDEX idx_energy_asset_house ON energy_asset(house_id);

ALTER TABLE meter ADD COLUMN house_id UUID;
UPDATE meter
SET house_id = (SELECT id FROM energy_house ORDER BY display_order, name LIMIT 1);
ALTER TABLE meter ALTER COLUMN house_id SET NOT NULL;
ALTER TABLE meter ADD CONSTRAINT fk_meter_house
    FOREIGN KEY (house_id) REFERENCES energy_house(id);
CREATE INDEX idx_meter_house ON meter(house_id);

ALTER TABLE energy_tariff ADD COLUMN house_id UUID;
UPDATE energy_tariff
SET house_id = (SELECT id FROM energy_house ORDER BY display_order, name LIMIT 1);
ALTER TABLE energy_tariff ALTER COLUMN house_id SET NOT NULL;
ALTER TABLE energy_tariff ADD CONSTRAINT fk_energy_tariff_house
    FOREIGN KEY (house_id) REFERENCES energy_house(id);
CREATE INDEX idx_energy_tariff_house ON energy_tariff(house_id);

INSERT INTO app_user_energy_house (app_user_id, house_id, access_role)
SELECT u.id, h.id, 'EDITOR'
FROM app_user u
CROSS JOIN energy_house h
ON CONFLICT DO NOTHING;
