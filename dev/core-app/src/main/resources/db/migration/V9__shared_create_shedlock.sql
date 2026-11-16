-- ShedLock (ADR 0013): one row per scheduled job name. A job runs only on the instance that moved
-- lock_until into the future; the others see the row locked and skip that run.
-- V1 gave the shared module no schema because it had no tables; this is its first.
CREATE SCHEMA IF NOT EXISTS shared;

CREATE TABLE shared.shedlock (
    name       varchar(64)  NOT NULL,
    lock_until timestamptz  NOT NULL,
    locked_at  timestamptz  NOT NULL,
    locked_by  varchar(255) NOT NULL,
    CONSTRAINT pk_shedlock PRIMARY KEY (name)
);

COMMENT ON TABLE shared.shedlock IS 'ShedLock: which instance runs a scheduled job, and until when (ADR 0013)';
