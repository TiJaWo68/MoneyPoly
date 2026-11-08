-- ============================================================================
-- SECURITY: ALIGN app_user.role WITH THE @Enumerated(EnumType.STRING) MAPPING
-- ============================================================================
-- The AppUser entity maps "role" as a plain string enum (@Enumerated STRING).
-- V2 created a PostgreSQL named enum (app_role) for this column, which
-- Hibernate binds to via a VARCHAR parameter and PostgreSQL rejects with
-- "column role is of type app_role but expression is of type character varying".
-- auth_provider is already modelled as VARCHAR(20); role is aligned the same way.

ALTER TABLE app_user ALTER COLUMN role DROP DEFAULT;
ALTER TABLE app_user ALTER COLUMN role TYPE VARCHAR(20) USING role::text;
ALTER TABLE app_user ALTER COLUMN role SET DEFAULT 'ROLE_USER';

DROP TYPE app_role;
