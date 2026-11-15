package com.trustledger.core.kyc.application;

import com.trustledger.core.shared.masking.Masking;
import java.time.LocalDate;
import java.util.UUID;

/** Minimum KYC input (FR-KYC-1). {@code toString()} masks every personal field, so logging it is safe. */
public record MinKycCommand(UUID userId, String fullName, LocalDate dateOfBirth, String mobile, String pan) {

    @Override
    public String toString() {
        return "MinKycCommand[userId=" + userId + ", fullName=" + Masking.name(fullName) + ", dateOfBirth=****"
                + ", mobile=" + Masking.phone(mobile) + ", pan=" + Masking.pan(pan) + "]";
    }
}
