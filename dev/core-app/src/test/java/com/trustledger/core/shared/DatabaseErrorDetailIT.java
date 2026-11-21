package com.trustledger.core.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.trustledger.core.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Day 14 B, finding M1: a constraint violation must not carry the row's values into exception messages, because
 * Hibernate's {@code SqlExceptionHelper} and {@code GlobalExceptionHandler} log those messages. By default PgJDBC
 * appends the server's {@code Detail: Failing row contains (...)}, i.e. every column, including free text that may
 * quote personal data. {@code logServerErrorDetail=false} (application.yml, all profiles) turns that off; the
 * constraint name stays, so the error is still diagnosable.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DatabaseErrorDetailIT {

    /** Stands in for personal data an officer might type into a free-text reason. */
    private static final String MARKER = "Customer phone 9876543210 said";

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void constraintViolationMessagesDoNotContainRowValues() {
        // A COMPLIANCE freeze without an officer breaks ck_wallet_freezes_officer; CHECKs run before the FK.
        Throwable thrown = catchThrowable(() -> jdbc.update("""
                insert into wallet.wallet_freezes (id, wallet_id, source, reason, frozen_by, frozen_at, created_at)
                values (?, ?, 'COMPLIANCE', ?, null, now(), now())""", UUID.randomUUID(), UUID.randomUUID(), MARKER));

        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            assertThat(t.getMessage()).doesNotContain(MARKER).doesNotContain("Failing row contains");
        }
        assertThat(rootMessage(thrown)).contains("ck_wallet_freezes_officer");
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage();
    }
}
