package com.trustledger.core.kyc.infrastructure;

import com.trustledger.core.kyc.domain.KycDocument;
import com.trustledger.core.kyc.domain.KycDocumentRepository;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data implementation of {@link KycDocumentRepository}. */
public interface JpaKycDocumentRepository extends JpaRepository<KycDocument, UUID>, KycDocumentRepository {
}
