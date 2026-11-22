-- Audit module, Day 20 (FR-A-1, FR-A-2, SPEC §5 GET /audit/events): the caller's user agent, and indexes for the
-- auditor's filters.
--
-- user_agent is part of the hashed content, but only when not null (ADR 0008, "adding a field to the hash"):
-- every row written before this column existed has NULL here and keeps its hash, so existing chains still verify.
-- The application normalises the header (control characters removed, cut to 256 characters) before hashing, so
-- the stored value is exactly the hashed one. ALTER TABLE ... ADD COLUMN fires no row trigger, so the append-only
-- triggers don't object.

ALTER TABLE audit.audit_events ADD COLUMN user_agent varchar(256);

-- GET /audit/events?actorId= (newest first) and ?from=&to=.
CREATE INDEX ix_audit_events_actor ON audit.audit_events (actor_id, seq);
CREATE INDEX ix_audit_events_occurred_at ON audit.audit_events (occurred_at);
