package com.trustledger.core.identity;

import com.trustledger.core.shared.masking.Masking;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The user's own identity data for a data export (FR-PR-2): the profile <b>in clear</b> and the whole consent
 * history. It exists only to be written into the user's export file. Callers must not log or store it anywhere
 * else; {@link #toString()} masks every personal field, so an accidental log line shows no personal data.
 *
 * @param consents every consent decision, oldest first (the history is append-only, FR-ID-3)
 */
public record PersonalDataExport(UUID userId, String email, String phone, String fullName, UserView.Status status,
        Instant registeredAt, List<ConsentRecord> consents) {

    public PersonalDataExport {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(status, "status");
        consents = List.copyOf(consents);
    }

    /** One consent decision as it was recorded, including the IP it came from. */
    public record ConsentRecord(String purpose, String policyVersion, boolean granted, Instant capturedAt,
            String ip) {

        /** The IP address is personal data too: never printed. */
        @Override
        public String toString() {
            return "ConsentRecord[purpose=" + purpose + ", policyVersion=" + policyVersion + ", granted=" + granted
                    + ", capturedAt=" + capturedAt + "]";
        }
    }

    @Override
    public String toString() {
        return "PersonalDataExport[userId=" + userId + ", email=" + Masking.email(email) + ", phone="
                + Masking.phone(phone) + ", fullName=" + Masking.name(fullName) + ", status=" + status
                + ", consents=" + consents.size() + "]";
    }
}
