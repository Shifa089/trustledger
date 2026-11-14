package com.trustledger.core.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.identity.application.TokenIdentity;
import com.trustledger.core.identity.application.UserProvisioningService;
import com.trustledger.core.identity.domain.UserRepository;
import com.trustledger.core.shared.crypto.BlindIndex;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

/** The module's public API as another module (payments, KYC) would use it. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IdentityApiIT {

    @Autowired
    IdentityApi identityApi;

    @Autowired
    UserProvisioningService provisioning;

    @Autowired
    UserRepository users;

    @Autowired
    BlindIndex blindIndex;

    @Autowired
    TransactionTemplate tx;

    @Test
    void findUserIdBySubject() {
        String subject = ApiMockMvc.newSubject();
        UUID id = provisioning.ensureUser(new TokenIdentity(subject, null, "Api User"));

        assertThat(identityApi.findUserId(subject)).contains(id);
        assertThat(identityApi.findUserId(ApiMockMvc.newSubject())).isEmpty();
        assertThat(identityApi.findUserId(" ")).isEmpty();
        assertThat(identityApi.findUserId(null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"%s", "+91 %s", "0%s", "91-%s"})
    void findByPhoneAcceptsCommonFormatsAndReturnsMaskedView(String format) {
        String phone = randomPhone();
        UUID id = userWithPhone("Phone Lookup User", phone);

        UserView view = identityApi.findByPhone(format.formatted(phone)).orElseThrow();

        assertThat(view.id()).isEqualTo(id);
        assertThat(view.isActive()).isTrue();
        assertThat(view.status()).isEqualTo(UserView.Status.ACTIVE);
        assertThat(view.maskedFullName()).isEqualTo("P*** L*** U***");
        assertThat(view.maskedPhone()).isEqualTo("******" + phone.substring(6));
    }

    @Test
    void unknownOrMalformedPhoneIsEmpty() {
        assertThat(identityApi.findByPhone(randomPhone())).isEmpty();
        assertThat(identityApi.findByPhone("12345")).isEmpty();
        assertThat(identityApi.findByPhone("")).isEmpty();
        assertThat(identityApi.findByPhone(null)).isEmpty();
    }

    @Test
    void phoneIsEncryptedAtRestButFoundThroughTheBlindIndex() {
        String phone = randomPhone();
        UUID id = userWithPhone("Cipher Check", phone);

        String stored = tx.execute(status -> users.findById(id).orElseThrow().getPhoneHash());
        assertThat(stored).isEqualTo(blindIndex.phone(phone)).doesNotContain(phone);
    }

    /** Until KYC (M3) sets the phone, tests set it through the domain method. */
    private UUID userWithPhone(String name, String phone) {
        UUID id = provisioning.ensureUser(new TokenIdentity(ApiMockMvc.newSubject(), null, name));
        tx.executeWithoutResult(status -> users.findById(id).orElseThrow()
                .changePhone(BlindIndex.normalisePhone(phone), blindIndex.phone(phone), Instant.now()));
        return id;
    }

    private static String randomPhone() {
        return "9" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
    }
}
