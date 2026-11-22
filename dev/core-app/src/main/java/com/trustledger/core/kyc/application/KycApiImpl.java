package com.trustledger.core.kyc.application;

import com.trustledger.core.kyc.KycApi;
import com.trustledger.core.kyc.KycStatusSummary;
import com.trustledger.core.kyc.domain.KycCase;
import com.trustledger.core.kyc.domain.KycCaseRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class KycApiImpl implements KycApi {

    private final KycCaseRepository cases;

    KycApiImpl(KycCaseRepository cases) {
        this.cases = cases;
    }

    @Override
    @Transactional(readOnly = true)
    public KycStatusSummary statusOf(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        List<KycCase> all = cases.findByUserIdOrderByCreatedAtAsc(userId);
        return new KycStatusSummary(KycQueryService.currentLevel(all), all.stream().map(KycApiImpl::summary).toList());
    }

    static KycStatusSummary.Case summary(KycCase kycCase) {
        return new KycStatusSummary.Case(kycCase.getId(), kycCase.getLevel(), kycCase.getStatus().name(),
                kycCase.getCreatedAt(), kycCase.getVerifiedAt(), kycCase.getApprovedAt(), kycCase.getRejectedAt(),
                kycCase.getRejectionReason());
    }
}
