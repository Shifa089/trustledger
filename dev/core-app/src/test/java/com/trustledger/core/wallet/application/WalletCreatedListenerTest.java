package com.trustledger.core.wallet.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.trustledger.core.wallet.WalletCreated;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WalletCreatedListenerTest {

    @Test
    void linksTheNewWalletToTheLedger() {
        WalletLedgerLinkService links = mock(WalletLedgerLinkService.class);
        UUID walletId = UUID.randomUUID();

        new WalletCreatedListener(links).on(new WalletCreated(walletId, UUID.randomUUID()));

        verify(links).link(walletId);
    }
}
