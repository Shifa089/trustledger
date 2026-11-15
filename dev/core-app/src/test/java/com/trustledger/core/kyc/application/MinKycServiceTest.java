package com.trustledger.core.kyc.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.trustledger.core.identity.IdentityApi;
import com.trustledger.core.kyc.KycLevel;
import com.trustledger.core.kyc.KycLevelUpgraded;
import com.trustledger.core.kyc.domain.KycCase;
import com.trustledger.core.kyc.domain.KycCaseRepository;
import com.trustledger.core.kyc.domain.KycStatus;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.shared.crypto.BlindIndex;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

/** Branches of minimum KYC that are hard to reach over HTTP: the insert race and the order of steps. */
class MinKycServiceTest {

    /** 20:00 UTC on 14 Nov = 01:30 on 15 Nov in India: the age check must use the Indian date. */
    private static final Instant NOW = Instant.parse("2026-11-14T20:00:00Z");
    private static final UUID USER = UUID.randomUUID();

    private final KycCaseRepository cases = mock(KycCaseRepository.class);
    private final IdentityApi identity = mock(IdentityApi.class);
    private final BlindIndex blindIndex = mock(BlindIndex.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private MinKycService service;

    @BeforeEach
    void setUp() {
        service = new MinKycService(cases, identity, blindIndex, events, new KycProperties(18),
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(blindIndex.value("pan", "ABCPE1234F")).thenReturn("h".repeat(64));
        when(cases.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    }

    private static MinKycCommand command(LocalDate dob) {
        return new MinKycCommand(USER, "Test Customer", dob, "9876543210", "ABCPE1234F");
    }

    @Test
    void approvesStoresAndPublishes() {
        KycCaseView view = service.submit(command(LocalDate.of(1995, 4, 15)));

        ArgumentCaptor<KycCase> saved = ArgumentCaptor.forClass(KycCase.class);
        verify(cases).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(KycStatus.APPROVED);
        assertThat(saved.getValue().getPanHash()).isEqualTo("h".repeat(64));
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(NOW);
        verify(identity).recordVerifiedPhone(USER, "9876543210");
        verify(events).publishEvent(new KycLevelUpgraded(USER, KycLevel.MIN));
        assertThat(view.maskedPan()).isEqualTo("XXXXX1234X");
        assertThat(view.status()).isEqualTo(KycStatus.APPROVED);
    }

    @Test
    void eighteenthBirthdayInIndiaCountsEvenWhileItIsStillTheDayBeforeInUtc() {
        service.submit(command(LocalDate.of(2008, 11, 15)));

        verify(events).publishEvent(new KycLevelUpgraded(USER, KycLevel.MIN));
    }

    @Test
    void tooYoungWritesNothing() {
        assertThatThrownBy(() -> service.submit(command(LocalDate.of(2008, 11, 16))))
                .isInstanceOfSatisfying(TrustLedgerException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.AGE_REQUIREMENT_NOT_MET));

        verifyNoInteractions(identity, events);
        verify(cases, never()).saveAndFlush(any());
    }

    @Test
    void futureDateOfBirthIsAValidationError() {
        assertThatThrownBy(() -> service.submit(command(LocalDate.of(2027, 1, 1))))
                .isInstanceOfSatisfying(TrustLedgerException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void existingMinCaseIsRejectedBeforeAnythingIsWritten() {
        when(cases.existsByUserIdAndLevelAndStatusNot(USER, KycLevel.MIN, KycStatus.REJECTED)).thenReturn(true);

        assertThatThrownBy(() -> service.submit(command(LocalDate.of(1995, 4, 15))))
                .isInstanceOfSatisfying(TrustLedgerException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.KYC_ALREADY_SUBMITTED));
        verifyNoInteractions(identity, events);
    }

    @Test
    void lostInsertRaceBecomesAlreadySubmittedAndPublishesNothing() {
        when(cases.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_kyc_cases_user_level_live"));

        assertThatThrownBy(() -> service.submit(command(LocalDate.of(1995, 4, 15))))
                .isInstanceOfSatisfying(TrustLedgerException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.KYC_ALREADY_SUBMITTED));
        verifyNoInteractions(events);
    }

    @Test
    void phoneConflictPropagatesSoTheTransactionRollsBackAndNothingIsPublished() {
        doThrow(new TrustLedgerException(ErrorCode.PHONE_ALREADY_REGISTERED, "taken"))
                .when(identity).recordVerifiedPhone(USER, "9876543210");

        assertThatThrownBy(() -> service.submit(command(LocalDate.of(1995, 4, 15))))
                .isInstanceOfSatisfying(TrustLedgerException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PHONE_ALREADY_REGISTERED));
        // The rollback of the inserted case is proven against PostgreSQL in MinKycIT.
        verifyNoInteractions(events);
    }

    @Test
    void commandToStringIsMasked() {
        String text = command(LocalDate.of(1995, 4, 15)).toString();

        assertThat(text).contains("XXXXX1234X", "******3210", "T*** C***")
                .doesNotContain("ABCPE1234F", "9876543210", "Test Customer", "1995");
    }
}
