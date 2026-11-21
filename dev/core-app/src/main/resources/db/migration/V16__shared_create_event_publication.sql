-- Spring Modulith event publication registry = the transactional outbox (M8, ADR 0005).
-- Every event that has a listener is stored here (one row per listener) in the SAME transaction that published it,
-- so a committed money movement can't lose its event and a rolled-back one can't leak one (no dual write).
--
-- Owned by Flyway, not by Modulith's schema initialisation (spring.modulith.events.jdbc.schema-initialization is
-- off): Modulith's script is CREATE TABLE IF NOT EXISTS, so after a Modulith upgrade it would silently keep an
-- outdated table. Layout copied from spring-modulith-events-jdbc 2.1.2, schemas/v2/schema-postgresql.sql.
-- A Modulith upgrade must be checked against that file and get its own migration if it changed.
--
-- Not append-only: Modulith updates status/completion_date and deletes completed rows during cleanup (M8 part B).
-- status values are Modulith's (PUBLISHED, PROCESSING, COMPLETED, FAILED, RESUBMITTED), so no CHECK here.
CREATE TABLE shared.event_publication (
    id                     uuid        NOT NULL,
    listener_id            text        NOT NULL,
    event_type             text        NOT NULL,
    serialized_event       text        NOT NULL,
    publication_date       timestamptz NOT NULL,
    completion_date        timestamptz,
    status                 text,
    completion_attempts    int,
    last_resubmission_date timestamptz,
    CONSTRAINT pk_event_publication PRIMARY KEY (id)
);

-- Modulith finds a publication by its serialized event when marking it completed.
CREATE INDEX event_publication_serialized_event_hash_idx ON shared.event_publication USING hash (serialized_event);
-- Incomplete publications (completion_date IS NULL) on restart, and the cleanup of completed ones.
CREATE INDEX event_publication_by_completion_date_idx ON shared.event_publication (completion_date);

COMMENT ON TABLE shared.event_publication IS 'Spring Modulith event publication registry: the outbox (ADR 0005)';
