package com.trustledger.core.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.trustledger.core.payments.LimitUsage;
import com.trustledger.core.payments.PaymentsApi;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.wallet.WalletApi;
import com.trustledger.core.wallet.WalletStatus;
import com.trustledger.core.wallet.WalletTier;
import com.trustledger.core.wallet.WalletView;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LimitUsageServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID WALLET = UUID.randomUUID();

    @Mock
    WalletApi wallets;

    @Mock
    PaymentsApi payments;

    @InjectMocks
    LimitUsageService service;

    @Test
    void readsTheUsageOfTheCallersOwnWallet() {
        Money zero = Money.zero(Money.INR);
        when(wallets.findByUser(USER)).thenReturn(Optional.of(
                new WalletView(WALLET, USER, WalletTier.FULL, WalletStatus.ACTIVE, zero, zero, zero)));
        LimitUsage usage = new LimitUsage(Money.parse("250.00"), Money.parse("4000.00"), Money.parse("6500.00"));
        when(payments.limitUsage(WALLET)).thenReturn(usage);

        assertThat(service.mine(USER)).isEqualTo(usage);
    }

    @Test
    void withoutAWalletItIsKycRequiredLikeWalletsMe() {
        when(wallets.findByUser(USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.mine(USER)).isInstanceOf(TrustLedgerException.class)
                .extracting(e -> ((TrustLedgerException) e).errorCode()).isEqualTo(ErrorCode.KYC_REQUIRED);
        verifyNoInteractions(payments);
    }

    @Test
    void needsAUser() {
        assertThatThrownBy(() -> service.mine(null)).isInstanceOf(NullPointerException.class);
    }
}
