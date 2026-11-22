package com.trustledger.core.kyc.application;

import com.trustledger.core.kyc.KycLevel;
import com.trustledger.core.kyc.domain.KycCase;
import com.trustledger.core.kyc.domain.KycCaseRepository;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side for the customer's own KYC status ({@code GET /kyc/me}). */
@Service
public class KycQueryService {

    private final KycCaseRepository cases;

    public KycQueryService(KycCaseRepository cases) {
        this.cases = cases;
    }

    @Transactional(readOnly = true)
    public KycOverview overview(UUID userId) {
        List<KycCase> all = cases.findByUserIdOrderByCreatedAtAsc(userId);
        return new KycOverview(currentLevel(all), all.stream().map(KycCaseView::of).toList());
    }

    /** The highest approved level among the cases, or {@code NONE}. */
    static String currentLevel(List<KycCase> all) {
        return all.stream()
                .filter(KycCase::isApproved)
                .map(KycCase::getLevel)
                .max(Comparator.naturalOrder()) // FULL > MIN (enum order)
                .map(KycLevel::name)
                .orElse(KycOverview.NONE);
    }
}
