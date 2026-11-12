package com.trustledger.core.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Proves the keys are validated when the application starts, without needing a database. */
class CryptoStartupTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CryptoConfig.class, AesGcmCipher.class, BlindIndex.class,
                    EncryptedStringConverter.class);

    @Test
    void startsWithValidKeys() {
        runner.withPropertyValues("trustledger.crypto.key=" + TestKeys.KEY_A_B64,
                        "trustledger.crypto.index-key=" + TestKeys.KEY_B_B64)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(EncryptedStringConverter.class).hasSingleBean(BlindIndex.class);
                });
    }

    @Test
    void failsToStartWithoutKeys() {
        runner.run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("trustledger.crypto.key is required"));
    }

    @Test
    void failsToStartWithShortKey() {
        runner.withPropertyValues("trustledger.crypto.key=AAAAAAAAAAAAAAAAAAAAAA==",
                        "trustledger.crypto.index-key=" + TestKeys.KEY_B_B64)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("must decode to 32 bytes"));
    }
}
