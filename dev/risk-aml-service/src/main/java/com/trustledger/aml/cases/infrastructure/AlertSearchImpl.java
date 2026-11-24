package com.trustledger.aml.cases.infrastructure;

import com.trustledger.aml.cases.domain.Alert;
import com.trustledger.aml.cases.domain.AlertFilter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * {@link AlertSearch}: only the filters that are set become predicates, so no {@code :param IS NULL} trick (which
 * PostgreSQL can't type for a null UUID). Newest first, then ID descending, so a page boundary never splits ties
 * differently between two requests. {@code ix_alerts_wallet_created} serves the wallet filter.
 */
class AlertSearchImpl implements AlertSearch {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Page<Alert> search(AlertFilter filter, Pageable pageable) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        CriteriaQuery<Alert> query = cb.createQuery(Alert.class);
        Root<Alert> alert = query.from(Alert.class);
        query.select(alert)
                .where(predicates(cb, alert, filter))
                .orderBy(cb.desc(alert.get("createdAt")), cb.desc(alert.get("id")));
        List<Alert> content = entityManager.createQuery(query)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        CriteriaQuery<Long> count = cb.createQuery(Long.class);
        Root<Alert> counted = count.from(Alert.class);
        count.select(cb.count(counted)).where(predicates(cb, counted, filter));
        long total = entityManager.createQuery(count).getSingleResult();

        return new PageImpl<>(content, pageable, total);
    }

    private static Predicate[] predicates(CriteriaBuilder cb, Root<Alert> alert, AlertFilter filter) {
        List<Predicate> predicates = new ArrayList<>(3);
        if (filter.ruleCode() != null) {
            predicates.add(cb.equal(alert.get("ruleCode"), filter.ruleCode()));
        }
        if (filter.severity() != null) {
            predicates.add(cb.equal(alert.get("severity"), filter.severity()));
        }
        if (filter.walletId() != null) {
            predicates.add(cb.equal(alert.get("walletId"), filter.walletId()));
        }
        return predicates.toArray(Predicate[]::new);
    }
}
