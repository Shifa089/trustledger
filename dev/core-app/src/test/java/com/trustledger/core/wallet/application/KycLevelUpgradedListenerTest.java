package com.trustledger.core.wallet.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.trustledger.core.kyc.KycLevel;
import com.trustledger.core.kyc.KycLevelUpgraded;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class KycLevelUpgradedListenerTest {

    private final WalletProvisioningService provisioning = mock(WalletProvisioningService.class);
    private final KycLevelUpgradedListener listener = new KycLevelUpgradedListener(provisioning);
    private final UUID userId = UUID.randomUUID();

    @Test
    void appliesTheLevel() {
        listener.on(new KycLevelUpgraded(userId, KycLevel.FULL));

        verify(provisioning).apply(userId, KycLevel.FULL);
    }

    @Test
    void lostInsertRaceIsAppliedAgainOnce() {
        doThrow(new DataIntegrityViolationException("uk_wallets_user")).doNothing()
                .when(provisioning).apply(userId, KycLevel.MIN);

        listener.on(new KycLevelUpgraded(userId, KycLevel.MIN));

        verify(provisioning, times(2)).apply(userId, KycLevel.MIN);
    }

    @Test
    void secondFailureIsNotSwallowed() {
        doThrow(new DataIntegrityViolationException("x")).when(provisioning).apply(userId, KycLevel.MIN);

        assertThatThrownBy(() -> listener.on(new KycLevelUpgraded(userId, KycLevel.MIN)))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(provisioning, times(2)).apply(userId, KycLevel.MIN);
    }
}
