package com.trustledger.core.payments.application;

import com.trustledger.core.payments.domain.TopupRepository;
import com.trustledger.core.payments.domain.TopupStatus;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.wallet.WalletLimits;
import com.trustledger.core.wallet.WalletView;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Top-up limits (SPEC §2 limits table, M7): money added this month stays within the tier's monthly load, and the
 * balance stays within the tier's maximum.
 * <p>
 * Both count top-ups that are still <em>open</em> (CREATED or PENDING) as if they will be paid: otherwise a customer
 * could open several orders that each fit on their own, pay them all, and land over the limit. The price is that
 * an abandoned checkout keeps its room reserved until the reconciler expires it (FR-P-6). FAILED and EXPIRED top-ups
 * never count. "This month" starts on the 1st at midnight in {@code trustledger.payments.limit-zone}
 * (Asia/Kolkata), by the top-up's creation time. Landing exactly on a limit is allowed.
 * <p>
 * <b>Call it while holding the wallet's row lock.</b> Every top-up creation for the wallet takes the same lock, so
 * two concurrent requests can't both see room for themselves.
 */
@Service
public class TopupLimitChecker {

    private final TopupRepository topups;
    private final ZoneId zone;
    private final Clock clock;

    public TopupLimitChecker(TopupRepository topups, PaymentsProperties properties, Clock clock) {
        this.topups = topups;
        this.zone = properties.limitZone();
        this.clock = clock;
    }

    /**
     * @param wallet a snapshot taken under the wallet's row lock
     * @throws TrustLedgerException {@code LIMIT_EXCEEDED}
     */
    public void check(WalletView wallet, WalletLimits limits, Money amount) {
        Objects.requireNonNull(wallet, "wallet");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(amount, "amount");

        long loaded = topups.sumCreatedSince(wallet.walletId(), monthStart(clock.instant()),
                TopupStatus.COUNTED_FOR_LOAD);
        if (Math.addExact(loaded, amount.amountMinor()) > limits.monthlyLoad().amountMinor()) {
            throw new TrustLedgerException(ErrorCode.LIMIT_EXCEEDED,
                    "This top-up would exceed your monthly limit of " + limits.monthlyLoad().toDecimalString()
                            + " for adding money.");
        }

        long open = topups.sumByStatus(wallet.walletId(), TopupStatus.OPEN);
        long balanceAfter = Math.addExact(Math.addExact(wallet.balance().amountMinor(), open), amount.amountMinor());
        if (balanceAfter > limits.maxBalance().amountMinor()) {
            throw new TrustLedgerException(ErrorCode.LIMIT_EXCEEDED,
                    "This top-up would take your balance above the maximum of "
                            + limits.maxBalance().toDecimalString() + ".");
        }
    }

    /** Start of the limit month that contains {@code now}, in the limit zone. */
    Instant monthStart(Instant now) {
        return now.atZone(zone).toLocalDate().withDayOfMonth(1).atStartOfDay(zone).toInstant();
    }
}
