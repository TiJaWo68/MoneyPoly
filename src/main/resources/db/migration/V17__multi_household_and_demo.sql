-- ============================================================================
-- MULTI-HOUSEHOLD ISOLATION & DEMO TENANT (#37)
-- ============================================================================

CREATE TABLE household (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL UNIQUE,
    is_demo BOOLEAN NOT NULL DEFAULT FALSE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    dashboard_warning_threshold NUMERIC(5, 2) NOT NULL DEFAULT 2.00,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO household (name, is_demo, is_active)
VALUES ('Haushalt 1', FALSE, TRUE);

ALTER TABLE app_user ADD COLUMN household_id UUID;
ALTER TABLE app_user ADD CONSTRAINT fk_app_user_household
    FOREIGN KEY (household_id) REFERENCES household(id) ON DELETE SET NULL;
CREATE INDEX idx_app_user_household ON app_user(household_id);

UPDATE app_user u
SET household_id = (SELECT id FROM household WHERE is_demo = FALSE ORDER BY created_at, name LIMIT 1)
WHERE EXISTS (
    SELECT 1 FROM app_user_role r
    WHERE r.app_user_id = u.id AND r.role IN ('ROLE_ADMIN', 'ROLE_USER')
);

ALTER TABLE finance_account ADD COLUMN household_id UUID;
UPDATE finance_account
SET household_id = (SELECT id FROM household WHERE is_demo = FALSE ORDER BY created_at, name LIMIT 1);
ALTER TABLE finance_account ALTER COLUMN household_id SET NOT NULL;
ALTER TABLE finance_account ADD CONSTRAINT fk_finance_account_household
    FOREIGN KEY (household_id) REFERENCES household(id);
CREATE INDEX idx_finance_account_household ON finance_account(household_id);

ALTER TABLE category ADD COLUMN household_id UUID;
UPDATE category
SET household_id = (SELECT id FROM household WHERE is_demo = FALSE ORDER BY created_at, name LIMIT 1);
ALTER TABLE category ALTER COLUMN household_id SET NOT NULL;
ALTER TABLE category ADD CONSTRAINT fk_category_household
    FOREIGN KEY (household_id) REFERENCES household(id);
ALTER TABLE category DROP CONSTRAINT IF EXISTS category_name_key;
ALTER TABLE category ADD CONSTRAINT uq_category_household_name UNIQUE (household_id, name);
CREATE INDEX idx_category_household ON category(household_id);

ALTER TABLE classification_rule ADD COLUMN household_id UUID;
UPDATE classification_rule
SET household_id = (SELECT id FROM household WHERE is_demo = FALSE ORDER BY created_at, name LIMIT 1);
ALTER TABLE classification_rule ALTER COLUMN household_id SET NOT NULL;
ALTER TABLE classification_rule ADD CONSTRAINT fk_classification_rule_household
    FOREIGN KEY (household_id) REFERENCES household(id);
CREATE INDEX idx_classification_rule_household ON classification_rule(household_id);

ALTER TABLE loan_contract ADD COLUMN household_id UUID;
UPDATE loan_contract
SET household_id = (SELECT id FROM household WHERE is_demo = FALSE ORDER BY created_at, name LIMIT 1);
ALTER TABLE loan_contract ALTER COLUMN household_id SET NOT NULL;
ALTER TABLE loan_contract ADD CONSTRAINT fk_loan_contract_household
    FOREIGN KEY (household_id) REFERENCES household(id);
CREATE INDEX idx_loan_contract_household ON loan_contract(household_id);

ALTER TABLE person ADD COLUMN household_id UUID;
UPDATE person
SET household_id = (SELECT id FROM household WHERE is_demo = FALSE ORDER BY created_at, name LIMIT 1);
ALTER TABLE person ALTER COLUMN household_id SET NOT NULL;
ALTER TABLE person ADD CONSTRAINT fk_person_household
    FOREIGN KEY (household_id) REFERENCES household(id);
ALTER TABLE person DROP CONSTRAINT IF EXISTS family_member_name_key;
ALTER TABLE person DROP CONSTRAINT IF EXISTS person_name_key;
ALTER TABLE person ADD CONSTRAINT uq_person_household_name UNIQUE (household_id, name);
CREATE INDEX idx_person_household ON person(household_id);
