package com.trustledger.core.identity.infrastructure;

import com.trustledger.core.identity.domain.Consent;
import com.trustledger.core.identity.domain.ConsentRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data implementation of {@link ConsentRepository}. */
public interface JpaConsentRepository extends JpaRepository<Consent, UUID>, ConsentRepository {

    /** PostgreSQL {@code DISTINCT ON}: one row per purpose, the highest seq; uses the (user, purpose, seq) index. */
    @Override
    @Query(value = """
            SELECT DISTINCT ON (purpose) *
            FROM identity.consents
            WHERE user_id = :userId
            ORDER BY purpose, seq DESC
            """, nativeQuery = true)
    List<Consent> findLatestPerPurpose(@Param("userId") UUID userId);

    /**
     * Native SQL: {@link Consent} is {@code @Immutable}, so Hibernate would refuse (or skip) a JPQL update on it. The
     * persistence context is deliberately not cleared: that would detach the caller's other entities (the user being
     * erased, the locked wallet) and silently drop their pending changes. Erasure never reads consents afterwards.
     */
    @Override
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE identity.consents SET ip = NULL WHERE user_id = :userId AND ip IS NOT NULL",
            nativeQuery = true)
    int clearIpAddresses(@Param("userId") UUID userId);
}
