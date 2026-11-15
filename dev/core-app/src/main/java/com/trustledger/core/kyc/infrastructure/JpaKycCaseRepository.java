package com.trustledger.core.kyc.infrastructure;

import com.trustledger.core.kyc.domain.KycCase;
import com.trustledger.core.kyc.domain.KycCaseRepository;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data implementation of {@link KycCaseRepository}: derived queries on the indexed columns. */
public interface JpaKycCaseRepository extends JpaRepository<KycCase, UUID>, KycCaseRepository {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from KycCase c where c.id = :id")
    Optional<KycCase> lockById(@Param("id") UUID id);
}
