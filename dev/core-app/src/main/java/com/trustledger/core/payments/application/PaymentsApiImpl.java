package com.trustledger.core.payments.application;

import com.trustledger.core.payments.PaymentsApi;
import com.trustledger.core.payments.TopupRecord;
import com.trustledger.core.payments.TransferRecord;
import com.trustledger.core.payments.domain.Topup;
import com.trustledger.core.payments.domain.TopupRepository;
import com.trustledger.core.payments.domain.Transfer;
import com.trustledger.core.payments.domain.TransferRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PaymentsApiImpl implements PaymentsApi {

    private final TransferRepository transfers;
    private final TopupRepository topups;

    PaymentsApiImpl(TransferRepository transfers, TopupRepository topups) {
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
