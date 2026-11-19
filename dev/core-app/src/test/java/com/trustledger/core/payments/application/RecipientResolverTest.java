package com.trustledger.core.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trustledger.core.identity.IdentityApi;
import com.trustledger.core.identity.UserView;
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

class RecipientResolverTest {

    private static final UUID SENDER_WALLET = UUID.randomUUID();
    private static final String MOBILE = "9876543210";

    private final IdentityApi identity = mock(IdentityApi.class);
    private final WalletApi wallets = mock(WalletApi.class);
    private final RecipientResolver resolver = new RecipientResolver(identity, wallets);

    private static WalletView wallet(UUID walletId, UUID userId) {
        Money zero = Money.zero(Money.INR);
        return new WalletView(walletId, userId, WalletTier.FULL, WalletStatus.ACTIVE, zero, zero, zero);
    }

    private static UserView user(UUID id, UserView.Status status) {
        return new UserView(id, status, "T*** U***", "******3210");
    }

    private void refused(TransferRecipient recipient, ErrorCode code) {
        assertThatThrownBy(() -> resolver.resolve(SENDER_WALLET, recipient)).isInstanceOf(TrustLedgerException.class)
                .extracting(e -> ((TrustLedgerException) e).errorCode()).isEqualTo(code);
    }

    @Test
    void resolvesByMobile() {
        UUID userId = UUID.randomUUID();
        WalletView receiver = wallet(UUID.randomUUID(), userId);
        when(identity.findByPhone(MOBILE)).thenReturn(Optional.of(user(userId, UserView.Status.ACTIVE)));
        when(wallets.findByUser(userId)).thenReturn(Optional.of(receiver));

        assertThat(resolver.resolve(SENDER_WALLET, TransferRecipient.byMobile(MOBILE))).isEqualTo(receiver);
    }

    @Test
    void resolvesByWalletId() {
        WalletView receiver = wallet(UUID.randomUUID(), UUID.randomUUID());
        when(wallets.findById(receiver.walletId())).thenReturn(Optional.of(receiver));

        assertThat(resolver.resolve(SENDER_WALLET, TransferRecipient.byWalletId(receiver.walletId())))
                .isEqualTo(receiver);
        verify(identity, never()).findByPhone(any());
    }

    @Test
    void unknownMobileIsNotFound() {
        when(identity.findByPhone(MOBILE)).thenReturn(Optional.empty());

        refused(TransferRecipient.byMobile(MOBILE), ErrorCode.NOT_FOUND);
    }

    @Test
    void erasedUserIsNotFound() {
        UUID userId = UUID.randomUUID();
        when(identity.findByPhone(MOBILE)).thenReturn(Optional.of(user(userId, UserView.Status.ERASED)));
        when(wallets.findByUser(userId)).thenReturn(Optional.of(wallet(UUID.randomUUID(), userId)));

        refused(TransferRecipient.byMobile(MOBILE), ErrorCode.NOT_FOUND);
    }

    @Test
    void userWithoutWalletIsNotFound() {
        UUID userId = UUID.randomUUID();
        when(identity.findByPhone(MOBILE)).thenReturn(Optional.of(user(userId, UserView.Status.ACTIVE)));
        when(wallets.findByUser(userId)).thenReturn(Optional.empty());

        refused(TransferRecipient.byMobile(MOBILE), ErrorCode.NOT_FOUND);
    }

    @Test
    void unknownWalletIsNotFound() {
        UUID walletId = UUID.randomUUID();
        when(wallets.findById(walletId)).thenReturn(Optional.empty());

        refused(TransferRecipient.byWalletId(walletId), ErrorCode.NOT_FOUND);
    }

    @Test
    void notFoundSaysTheSameWhateverTheReason() {
        UUID walletId = UUID.randomUUID();
        when(wallets.findById(walletId)).thenReturn(Optional.empty());
        when(identity.findByPhone(MOBILE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve(SENDER_WALLET, TransferRecipient.byWalletId(walletId)))
                .hasMessage("Recipient not found.");
        assertThatThrownBy(() -> resolver.resolve(SENDER_WALLET, TransferRecipient.byMobile(MOBILE)))
                .hasMessage("Recipient not found.");
    }

    @Test
    void ownWalletByIdIsRefused() {
        when(wallets.findById(SENDER_WALLET)).thenReturn(Optional.of(wallet(SENDER_WALLET, UUID.randomUUID())));

        refused(TransferRecipient.byWalletId(SENDER_WALLET), ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void ownMobileIsRefused() {
        UUID userId = UUID.randomUUID();
        when(identity.findByPhone(MOBILE)).thenReturn(Optional.of(user(userId, UserView.Status.ACTIVE)));
        when(wallets.findByUser(userId)).thenReturn(Optional.of(wallet(SENDER_WALLET, userId)));

        refused(TransferRecipient.byMobile(MOBILE), ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void exactlyOneOfMobileAndWalletIsRequired() {
        refused(new TransferRecipient(null, null), ErrorCode.VALIDATION_FAILED);
        refused(new TransferRecipient("  ", null), ErrorCode.VALIDATION_FAILED);
        refused(new TransferRecipient(MOBILE, UUID.randomUUID()), ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void toStringHidesTheMobileNumber() {
        assertThat(TransferRecipient.byMobile(MOBILE).toString()).doesNotContain("3210");
    }
}
