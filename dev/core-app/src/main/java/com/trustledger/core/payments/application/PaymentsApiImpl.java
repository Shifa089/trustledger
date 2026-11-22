package com.trustledger.core.payments.application;

import com.trustledger.core.payments.PaymentsApi;
import com.trustledger.core.payments.TopupRecord;
import com.trustledger.core.payments.TransactionCursor;
import com.trustledger.core.payments.TransactionFilter;
import com.trustledger.core.payments.TransactionPage;
import com.trustledger.core.payments.TransactionSlice;
import com.trustledger.core.payments.TransactionSort;
import com.trustledger.core.payments.TransferRecord;
import com.trustledger.core.payments.domain.Topup;
import com.trustledger.core.payments.domain.TopupRepository;
import com.trustledger.core.payments.domain.TopupStatus;
import com.trustledger.core.payments.domain.Transfer;
import com.trustledger.core.payments.domain.TransferRepository;
import com.trustledger.core.payments.domain.TransferStatus;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PaymentsApiImpl implements PaymentsApi {

    /** Transfers that may still move money: not completed, rejected, failed or reversed yet. */
    private static final Set<TransferStatus> UNFINISHED = EnumSet.of(TransferStatus.INITIATED,
            TransferStatus.HELD_FOR_REVIEW);

    private final TransferRepository transfers;
    private final TopupRepository topups;
    private final WalletTransactionQueryService history;

    PaymentsApiImpl(TransferRepository transfers, TopupRepository topups, WalletTransactionQueryService history) {
        this.history = history;
        this.transfers = transfers;
        this.topups = topups;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TransferRecord> transfersOf(UUID walletId) {
        Objects.requireNonNull(walletId, "walletId");
        return transfers.findInvolving(walletId).stream().map(PaymentsApiImpl::record).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TopupRecord> topupsOf(UUID walletId) {
        Objects.requireNonNull(walletId, "walletId");
        return topups.findByWalletIdOrderByCreatedAtAscIdAsc(walletId).stream().map(PaymentsApiImpl::record).toList();
    }

    /** One user's history, so filtering in memory is cheap; it reuses the export's queries and their indexes. */
    @Override
    @Transactional(readOnly = true)
    public boolean hasMoneyInFlight(UUID walletId) {
        Objects.requireNonNull(walletId, "walletId");
        return transfers.findInvolving(walletId).stream().anyMatch(t -> UNFINISHED.contains(t.getStatus()))
                || topups.findByWalletIdOrderByCreatedAtAscIdAsc(walletId).stream()
                        .anyMatch(t -> TopupStatus.OPEN.contains(t.getStatus()));
    }

    @Override
    public TransactionPage transactions(TransactionFilter filter, TransactionSort sort, int page, int size) {
        return history.page(filter, sort, page, size);
    }

    @Override
    public TransactionSlice transactionsAfter(TransactionFilter filter, TransactionCursor after, int size) {
        return history.after(filter, after, size);
    }

    static TransferRecord record(Transfer transfer) {
        return new TransferRecord(transfer.getId(), transfer.getSenderWalletId(), transfer.getReceiverWalletId(),
                transfer.amount(), transfer.getStatus().name(), transfer.getNote(), transfer.getCreatedAt(),
                transfer.getUpdatedAt());
    }

    static TopupRecord record(Topup topup) {
        return new TopupRecord(topup.getId(), topup.getWalletId(), topup.amount(), topup.getStatus().name(),
                topup.getGatewayOrderId(), topup.getGatewayPaymentId(), topup.getCreatedAt(), topup.getUpdatedAt());
    }
}
