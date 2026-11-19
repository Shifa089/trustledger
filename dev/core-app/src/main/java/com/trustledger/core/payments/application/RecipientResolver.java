package com.trustledger.core.payments.application;

import com.trustledger.core.identity.IdentityApi;
import com.trustledger.core.identity.UserView;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.wallet.WalletApi;
import com.trustledger.core.wallet.WalletView;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Finds the receiver's wallet for a transfer (FR-P-1, "Transfer flow" step 2): by mobile number through the
 * identity module's blind index, or by wallet ID.
 * <p>
 * An unknown number, an erased user, a user without a wallet and an unknown wallet ID all give the <em>same</em>
 * 404, so the endpoint can't be used to find out which mobile numbers are registered. Sending to yourself is
 * refused here, before any lock is taken. Wallet status (frozen, closed) is checked later, under the lock.
 */
@Service
public class RecipientResolver {

    private final IdentityApi identity;
    private final WalletApi wallets;

    public RecipientResolver(IdentityApi identity, WalletApi wallets) {
        this.identity = identity;
        this.wallets = wallets;
    }

    /**
     * @return the receiver's wallet, not locked
     * @throws TrustLedgerException {@code VALIDATION_FAILED} (not exactly one of mobile and wallet ID, or the
     *         sender's own wallet), {@code NOT_FOUND} (no such recipient)
     */
    public WalletView resolve(UUID senderWalletId, TransferRecipient recipient) {
        Objects.requireNonNull(senderWalletId, "senderWalletId");
        Objects.requireNonNull(recipient, "recipient");
        boolean byMobile = recipient.mobile() != null && !recipient.mobile().isBlank();
        boolean byWallet = recipient.walletId() != null;
        if (byMobile == byWallet) {
            throw new TrustLedgerException(ErrorCode.VALIDATION_FAILED,
                    "Give exactly one of recipientMobile and recipientWalletId.");
        }
        WalletView receiver = (byMobile
                ? identity.findByPhone(recipient.mobile())
                        .filter(UserView::isActive)
                        .flatMap(user -> wallets.findByUser(user.id()))
                : wallets.findById(recipient.walletId()))
                .orElseThrow(() -> new TrustLedgerException(ErrorCode.NOT_FOUND, "Recipient not found."));
        if (receiver.walletId().equals(senderWalletId)) {
            throw new TrustLedgerException(ErrorCode.VALIDATION_FAILED, "You cannot send money to your own wallet.");
        }
        return receiver;
    }
}
