-- One schema per core-app module (SPEC §4). No foreign keys across these schemas.
-- The aml schema belongs to risk-aml-service and is created by its own migrations.
-- Modules without tables of their own (shared, reporting) get no schema.
CREATE SCHEMA IF NOT EXISTS identity;
CREATE SCHEMA IF NOT EXISTS kyc;
CREATE SCHEMA IF NOT EXISTS wallet;
CREATE SCHEMA IF NOT EXISTS ledger;
CREATE SCHEMA IF NOT EXISTS payments;
CREATE SCHEMA IF NOT EXISTS audit;
CREATE SCHEMA IF NOT EXISTS privacy;
CREATE SCHEMA IF NOT EXISTS notification;
