-- ============================================================================
-- SECURITY: MULTIPLE ROLES PER USER (3-STAGE ROLE MODEL)
-- ============================================================================
-- The admin module requires assigning multiple roles (ROLE_ADMIN, ROLE_USER,
-- ROLE_BORROWER) per account. The single app_user.role column is replaced by
-- the app_user_role join table while preserving existing assignments.
-- ROLE_BORROWER is added to the role model (see pflichtenheft Epic 5.2).

CREATE TABLE app_user_role (
    app_user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL,
    CONSTRAINT pk_app_user_role PRIMARY KEY (app_user_id, role)
);

INSERT INTO app_user_role (app_user_id, role)
SELECT id, role FROM app_user WHERE role IS NOT NULL;

ALTER TABLE app_user DROP COLUMN role;
