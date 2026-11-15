package com.trustledger.core.kyc.infrastructure;

import com.trustledger.core.kyc.domain.KycCase;
import com.trustledger.core.kyc.domain.KycCaseRepository;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data implementation of {@link KycCaseRepository}: derived queries on the indexed columns. */
public interface JpaKycCaseRepository extends JpaRepository<KycCase, UUID>, KycCaseRepository {
}
