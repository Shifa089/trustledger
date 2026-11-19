package com.trustledger.core.payments.application;

import java.util.UUID;

/**
 * Who to send money to (FR-P-1): exactly one of a mobile number or a wallet ID. Checked by
 * {@link RecipientResolver}, so a request record can map straight into it.
 */
public record TransferRecipient(String mobile, UUID walletId) {

    public static TransferRecipient byMobile(String mobile) {
        return new TransferRecipient(mobile, null);
    }

    public static TransferRecipient byWalletId(UUID walletId) {
        return new TransferRecipient(null, walletId);
    }

    /** Masks the mobile number: the record may end up in a log or an exception message. */
    @Override
    public String toString() {
        return "TransferRecipient[mobile=" + (mobile == null ? "null" : "***") + ", walletId=" + walletId + "]";
    }
}
