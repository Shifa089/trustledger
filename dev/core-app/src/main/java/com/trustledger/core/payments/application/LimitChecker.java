package com.trustledger.core.payments.application;

import com.trustledger.core.payments.domain.TransferRepository;
import com.trustledger.core.payments.domain.TransferStatus;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.wallet.WalletLimits;
import com.trustledger.core.wallet.WalletView;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Transfer limits (FR-P-2, "Transfer flow" step 4): the sender's tier must allow P2P, outgoing transfers today
 * and this month stay within the tier's limits, and the receiver's balance stays within its cap.
 * <p>
 * "Today" and "this month" start at midnight and on the 1st in {@code trustledger.payments.limit-zone}
 * (Asia/Kolkata), computed from the injected {@link Clock}. A transfer counts if it is COMPLETED or
 * HELD_FOR_REVIEW: held money is on its way out, and not counting it would let a customer queue up holds beyond
 * the limit. Landing exactly on a limit is allowed; one paisa over is {@code LIMIT_EXCEEDED}.
 * <p>
 * <b>Call it while holding the sender wallet's row lock.</b> Every transfer from that wallet takes the same lock
 * first, so the sums can't change between the check and the commit. Without the lock two concurrent transfers
 * could both see room for themselves and together exceed the limit.
 */
@Service
public class LimitChecker {

    /** Transfers that use up the limit. */
    static final Set<TransferStatus> COUNTED = EnumSet.of(TransferStatus.COMPLETED, TransferStatus.HELD_FOR_REVIEW);

    private final TransferRepository transfers;
    private final ZoneId zone;
    private final Clock clock;

    public LimitChecker(TransferRepository transfers, PaymentsProperties properties, Clock clock) {
        this.transfers = transfers;
        this.zone = properties.limitZone();
        this.clock = clock;
    }

    /**
     * @throws TrustLedgerException {@code KYC_REQUIRED} (the tier can't send P2P), {@code LIMIT_EXCEEDED}
     */
    public void checkSender(UUID senderWalletId, WalletLimits limits, Money amount) {
        Objects.requireNonNull(senderWalletId, "senderWalletId");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(amount, "amount");
        if (!limits.p2pAllowed()) {
            throw new TrustLedgerException(ErrorCode.KYC_REQUIRED, "Full KYC is required to send money.");
        }
        Windows windows = windowsAt(clock.instant());
        requireWithin(senderWalletId, windows.dayStart(), limits.dailyTransfer(), amount, "daily");
        if (limits.monthlyTransfer() != null) {
            requireWithin(senderWalletId, windows.monthStart(), limits.monthlyTransfer(), amount, "monthly");
        }
    }

    /**
     * The receiver's balance after the credit must not exceed its tier's maximum balance (FR-P-2). The message
     * says nothing about the receiver's balance.
     *
     * @param receiver a snapshot taken under the receiver's row lock
     */
    public void checkReceiverCap(WalletView receiver, WalletLimits receiverLimits, Money amount) {
        Objects.requireNonNull(receiver, "receiver");
        Objects.requireNonNull(receiverLimits, "receiverLimits");
        Objects.requireNonNull(amount, "amount");
        if (receiver.balance().plus(amount).isGreaterThan(receiverLimits.maxBalance())) {
            throw new TrustLedgerException(ErrorCode.LIMIT_EXCEEDED,
                    "The recipient's wallet cannot accept this amount.");
        }
    }

    /** Start of the limit day and month that contain {@code now}, in the limit zone. */
    Windows windowsAt(Instant now) {
        LocalDate today = now.atZone(zone).toLocalDate();
        return new Windows(today.atStartOfDay(zone).toInstant(),
                today.withDayOfMonth(1).atStartOfDay(zone).toInstant());
    }

    private void requireWithin(UUID senderWalletId, Instant from, Money limit, Money amount, String period) {
        long used = transfers.sumOutgoingSince(senderWalletId, from, COUNTED);
        long after = Math.addExact(used, amount.amountMinor());
        if (after > limit.amountMinor()) {
            throw new TrustLedgerException(ErrorCode.LIMIT_EXCEEDED,
                    "This transfer would exceed your " + period + " transfer limit of "
                            + limit.toDecimalString() + ".");
        }
    }

    record Windows(Instant dayStart, Instant monthStart) {
    }
}
