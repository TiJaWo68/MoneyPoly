-- ============================================================================
-- SECURITY: TECHNICAL LOGIN ACCOUNTS AND AUTHORIZATION ROLES
-- ============================================================================
-- app_user is intentionally decoupled from the financial domain. The optional
-- family_member_id link is the only bridge to the finance module and must not
-- be required for maintenance accounts.

CREATE TYPE app_role AS ENUM ('ROLE_ADMIN', 'ROLE_USER');

CREATE TABLE app_user (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(120) NOT NULL UNIQUE,
    password_hash VARCHAR(255),              -- NULL for pure OAuth2 accounts
    auth_provider VARCHAR(20) NOT NULL DEFAULT 'LOCAL',
    provider_id VARCHAR(255) UNIQUE,         -- OIDC 'sub' claim
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    role app_role NOT NULL DEFAULT 'ROLE_USER',
    preferred_locale VARCHAR(10) NOT NULL DEFAULT 'de',
    family_member_id UUID UNIQUE REFERENCES family_member(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_login_at TIMESTAMPTZ
);

-- username, email and provider_id are already indexed by their UNIQUE constraints.
-- Composite index for provider-scoped lookups.
CREATE INDEX idx_app_user_provider ON app_user(auth_provider, provider_id);
