package com.trustledger.core.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.identity.IdentityApi;
import com.trustledger.core.identity.application.TokenIdentity;
import com.trustledger.core.identity.application.UserProvisioningService;
import com.trustledger.core.kyc.KycLevel;
import com.trustledger.core.payments.application.RecipientResolver;
import com.trustledger.core.payments.application.TransferRecipient;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.wallet.WalletApi;
import com.trustledger.core.wallet.WalletView;
import com.trustledger.core.wallet.application.WalletProvisioningService;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Recipient lookup through the real identity blind index and the real wallets (FR-P-1). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RecipientResolverIT {

    @Autowired
    RecipientResolver resolver;

    @Autowired
    UserProvisioningService users;

    @Autowired
    IdentityApi identity;

    @Autowired
    WalletProvisioningService wallets;

    @Autowired
    WalletApi walletApi;

    private static String randomPhone() {
        return "9" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
    }

    private UUID userWithPhone(String phone) {
        UUID userId = users.ensureUser(new TokenIdentity(ApiMockMvc.newSubject(), null, "Recipient Test User"));
        identity.recordVerifiedPhone(userId, phone);
        return userId;
    }

    private WalletView walletOf(UUID userId) {
        wallets.apply(userId, KycLevel.FULL);
        return walletApi.findByUser(userId).orElseThrow();
    }

    private UUID senderWallet() {
        return walletOf(userWithPhone(randomPhone())).walletId();
    }

    private void refused(UUID sender, TransferRecipient recipient, ErrorCode code) {
        assertThatThrownBy(() -> resolver.resolve(sender, recipient)).isInstanceOf(TrustLedgerException.class)
                .extracting(e -> ((TrustLedgerException) e).errorCode()).isEqualTo(code);
    }

    @Test
    void findsTheReceiverByMobileInAnyCommonFormat() {
        String phone = randomPhone();
        WalletView receiver = walletOf(userWithPhone(phone));
        UUID sender = senderWallet();

        assertThat(resolver.resolve(sender, TransferRecipient.byMobile(phone)).walletId())
                .isEqualTo(receiver.walletId());
        assertThat(resolver.resolve(sender, TransferRecipient.byMobile("+91 " + phone)).walletId())
                .isEqualTo(receiver.walletId());
    }

    @Test
    void findsTheReceiverByWalletId() {
        WalletView receiver = walletOf(userWithPhone(randomPhone()));

        assertThat(resolver.resolve(senderWallet(), TransferRecipient.byWalletId(receiver.walletId())))
                .isEqualTo(receiver);
    }

    @Test
    void unknownMobileOrWalletIsNotFound() {
        UUID sender = senderWallet();

        refused(sender, TransferRecipient.byMobile(randomPhone()), ErrorCode.NOT_FOUND);
        refused(sender, TransferRecipient.byMobile("12345"), ErrorCode.NOT_FOUND);
        refused(sender, TransferRecipient.byWalletId(UUID.randomUUID()), ErrorCode.NOT_FOUND);
    }

    @Test
    void userWithoutWalletIsNotFound() {
        String phone = randomPhone();
        userWithPhone(phone);

        refused(senderWallet(), TransferRecipient.byMobile(phone), ErrorCode.NOT_FOUND);
    }

    @Test
    void ownMobileIsRefused() {
        String phone = randomPhone();
        WalletView mine = walletOf(userWithPhone(phone));

        refused(mine.walletId(), TransferRecipient.byMobile(phone), ErrorCode.VALIDATION_FAILED);
        refused(mine.walletId(), TransferRecipient.byWalletId(mine.walletId()), ErrorCode.VALIDATION_FAILED);
    }
}
