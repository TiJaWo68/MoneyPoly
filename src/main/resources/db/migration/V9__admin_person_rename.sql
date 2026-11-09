-- ============================================================================
-- DOMAIN: RENAME family_member TO person (NEUTRAL PRIVATE-LOAN TERMINOLOGY)
-- ============================================================================
-- Private loans are not necessarily family-internal. The domain concept is a
-- "person" acting as lender or borrower, so the misleading "family" prefix is
-- removed from the table, the referencing app_user column and the historical
-- role value. The former PARENT role maps to LENDER.

UPDATE family_member SET role = 'LENDER' WHERE role = 'PARENT';

ALTER TABLE family_member RENAME TO person;

ALTER TABLE app_user RENAME COLUMN family_member_id TO person_id;
