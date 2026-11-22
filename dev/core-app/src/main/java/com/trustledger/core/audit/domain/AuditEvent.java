package com.trustledger.core.audit.domain;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The hashed content of one audit row: everything except {@code seq}, the hashes and {@code created_at}.
 * Built from the masked entry and the context when written, and rebuilt from the stored row when the chain is
 * verified; both must give the same {@link #canonicalJson()}.
 * <p>
 * {@code occurredAt} is cut to microseconds, the precision of {@code timestamptz}, and {@code userAgent} is
 * normalised, so the stored value is exactly the hashed one.
 * <p>
 * <b>Adding a field to the hash</b> (ADR 0008): a field added after the first version is written to the canonical
 * JSON <em>only when it is not null</em>, so every row written before it existed keeps its hash. {@code userAgent}
 * (Day 20) is the first such field.
 */
public record AuditEvent(Instant occurredAt, UUID actorId, String actorRole, String action, String entityType,
        UUID entityId, Map<String, Object> before, Map<String, Object> after, String ip, String correlationId,
        String userAgent) {

    /** Fixed width, always six fraction digits and a {@code Z}: one text per instant. */
    private static final DateTimeFormatter OCCURRED_AT = DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    private static final Pattern CODE = Pattern.compile("[A-Z_]{1,64}");
    private static final int MAX_IP_LENGTH = 64;

    /** The width of {@code audit_events.user_agent}; a longer header is cut. */
    public static final int MAX_USER_AGENT_LENGTH = 256;

    /** C0 controls, DEL and C1 controls: never useful in a user agent, and NUL can't be stored in Postgres text. */
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x1F\\x7F-\\x9F]");

    public AuditEvent {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(actorRole, "actorRole");
        Objects.requireNonNull(entityId, "entityId");
        if (action == null || !CODE.matcher(action).matches()) {
            throw new IllegalArgumentException("action must be an upper-case code");
        }
        if (entityType == null || !CODE.matcher(entityType).matches()) {
            throw new IllegalArgumentException("entityType must be an upper-case code");
        }
        occurredAt = occurredAt.truncatedTo(ChronoUnit.MICROS);
        before = copy(before);
        after = copy(after);
        // An address that can't be stored is dropped rather than failing the business change.
        ip = ip != null && ip.length() <= MAX_IP_LENGTH ? ip : null;
        userAgent = normaliseUserAgent(userAgent);
    }

    /** An event without a user agent: everything written before Day 20, and system work. */
    public AuditEvent(Instant occurredAt, UUID actorId, String actorRole, String action, String entityType,
            UUID entityId, Map<String, Object> before, Map<String, Object> after, String ip, String correlationId) {
        this(occurredAt, actorId, actorRole, action, entityType, entityId, before, after, ip, correlationId, null);
    }

    /** A new event from the masked entry and the committing thread's context. */
    public static AuditEvent of(Instant occurredAt, AuditEntry maskedEntry, AuditContext context) {
        return new AuditEvent(occurredAt, context.actor().actorId(), context.actor().role(),
                maskedEntry.action().name(), maskedEntry.entityType().name(), maskedEntry.entityId(),
                castMap(maskedEntry.before()), castMap(maskedEntry.after()), context.ip(), context.correlationId(),
                context.userAgent());
    }

    /** The canonical JSON that, appended to the previous hash, is hashed (ADR 0008). */
    public String canonicalJson() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("occurredAt", OCCURRED_AT.format(occurredAt));
        fields.put("actorId", actorId == null ? null : actorId.toString());
        fields.put("actorRole", actorRole);
        fields.put("action", action);
        fields.put("entityType", entityType);
        fields.put("entityId", entityId.toString());
        fields.put("before", before);
        fields.put("after", after);
        fields.put("ip", ip);
        fields.put("correlationId", correlationId);
        // Added on Day 20: left out when null, so older rows keep their hash.
        if (userAgent != null) {
            fields.put("userAgent", userAgent);
        }
        return CanonicalJson.write(fields);
    }

    /** {@code before} as canonical JSON for the jsonb column, or {@code null}. */
    public String beforeJson() {
        return before == null ? null : CanonicalJson.write(before);
    }

    /** {@code after} as canonical JSON for the jsonb column, or {@code null}. */
    public String afterJson() {
        return after == null ? null : CanonicalJson.write(after);
    }

    /** Control characters removed, trimmed, cut to {@link #MAX_USER_AGENT_LENGTH}; blank becomes {@code null}. */
    static String normaliseUserAgent(String userAgent) {
        if (userAgent == null) {
            return null;
        }
        String cleaned = CONTROL.matcher(userAgent).replaceAll("").strip();
        if (cleaned.length() > MAX_USER_AGENT_LENGTH) {
            cleaned = cleaned.substring(0, MAX_USER_AGENT_LENGTH).strip();
        }
        return cleaned.isEmpty() ? null : cleaned;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<String, ?> map) {
        return (Map<String, Object>) map;
    }

    private static Map<String, Object> copy(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        // Fails fast on an unsupported value type, before anything is locked or written.
        CanonicalJson.write(map);
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }
}
