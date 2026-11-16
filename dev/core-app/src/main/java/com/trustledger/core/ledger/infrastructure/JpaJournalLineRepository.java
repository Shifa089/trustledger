package com.trustledger.core.ledger.infrastructure;

import com.trustledger.core.ledger.Direction;
import com.trustledger.core.ledger.domain.AccountSideTotal;
import com.trustledger.core.ledger.domain.JournalLine;
import com.trustledger.core.ledger.domain.JournalLineRepository;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data implementation of {@link JournalLineRepository}. Uses {@code ix_journal_lines_account_entry}. */
public interface JpaJournalLineRepository extends JpaRepository<JournalLine, UUID>, JournalLineRepository {

    @Override
    @Query("select coalesce(sum(l.amountMinor), 0L) from JournalLine l "
            + "where l.accountId = :accountId and l.direction = :direction")
    long sumAmount(@Param("accountId") UUID accountId, @Param("direction") Direction direction);

    @Override
    @Query("select new com.trustledger.core.ledger.domain.AccountSideTotal(l.accountId, l.direction, "
            + "sum(l.amountMinor)) from JournalLine l where l.accountId in :accountIds "
            + "group by l.accountId, l.direction")
    List<AccountSideTotal> sumByAccountAndDirection(@Param("accountIds") Collection<UUID> accountIds);
}
