package com.trustledger.core.privacy.infrastructure;

import com.trustledger.core.privacy.domain.AmlCaseProjection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link AmlCaseProjection} with PostgreSQL upserts: one statement per event, no read-then-write window, so two
 * deliveries of the same event (or the two events of one case) racing each other still end in the right state.
 * Joins the caller's transaction.
 */
@Repository
class JdbcAmlCaseProjection implements AmlCaseProjection {

    private final JdbcClient jdbc;

    JdbcAmlCaseProjection(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void apply(UUID caseId, UUID walletId, boolean open, Instant changedAt, Instant now) {
        String sql = open
                // Opened: only a case we have never seen. A late "opened" after "finished" must not reopen it.
                ? """
                  INSERT INTO privacy.aml_cases (case_id, wallet_id, open, changed_at, updated_at)
                  VALUES (:caseId, :walletId, true, :changedAt, :now)
                  ON CONFLICT (case_id) DO NOTHING
                  """
                // Finished: final, whatever we had before (or nothing, if "opened" was lost or is still on its way).
                : """
                  INSERT INTO privacy.aml_cases (case_id, wallet_id, open, changed_at, updated_at)
                  VALUES (:caseId, :walletId, false, :changedAt, :now)
                  ON CONFLICT (case_id) DO UPDATE SET open = false, changed_at = EXCLUDED.changed_at,
                      updated_at = EXCLUDED.updated_at
                  WHERE privacy.aml_cases.open
                  """;
        jdbc.sql(sql)
                .param("caseId", caseId)
                .param("walletId", walletId)
                .param("changedAt", Timestamp.from(changedAt))
                .param("now", Timestamp.from(now))
                .update();
    }

    @Override
    public boolean hasOpenCase(UUID walletId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM privacy.aml_cases WHERE wallet_id = :walletId AND open)")
                .param("walletId", walletId)
                .query(Boolean.class)
                .single();
    }
}
