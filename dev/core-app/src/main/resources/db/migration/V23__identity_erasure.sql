-- FR-PR-2 erasure, identity side (M10 Day 21 B).

-- 1. An ERASED user keeps no personal data: no e-mail, phone or name ciphertext, and no blind index (an HMAC of the
--    phone or e-mail would still let anyone holding the key confirm "was this number a customer?").
ALTER TABLE identity.users ADD CONSTRAINT ck_users_erased_has_no_personal_data CHECK (
    status <> 'ERASED' OR (email_enc IS NULL AND email_hash IS NULL AND phone_enc IS NULL AND phone_hash IS NULL
                           AND full_name_enc IS NULL));

-- 2. Consent rows stay append-only evidence (who agreed to which policy version, when), but erasure may blank the
--    IP address, and nothing else. V2 announced this relaxation; every other UPDATE, any DELETE and TRUNCATE are
--    still refused.
CREATE OR REPLACE FUNCTION identity.reject_consent_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF OLD.ip IS NOT NULL AND NEW.ip IS NULL
           AND NEW.id = OLD.id AND NEW.seq = OLD.seq AND NEW.user_id = OLD.user_id AND NEW.purpose = OLD.purpose
           AND NEW.policy_version = OLD.policy_version AND NEW.granted = OLD.granted
           AND NEW.captured_at = OLD.captured_at AND NEW.created_at = OLD.created_at THEN
            RETURN NEW;
        END IF;
    END IF;
    RAISE EXCEPTION 'identity.consents is append-only: % is not allowed', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;
