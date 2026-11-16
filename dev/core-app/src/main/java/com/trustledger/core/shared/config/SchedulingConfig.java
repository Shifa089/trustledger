package com.trustledger.core.shared.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduled jobs and their cluster-wide lock (ADR 0013).
 * <p>
 * Jobs take the lock explicitly through {@link LockingTaskExecutor}, not with {@code @SchedulerLock}, so the
 * nightly trigger and a manual admin run share one code path and one lock. Lock times come from the database
 * clock ({@code usingDbTime}), so instances with skewed clocks still agree on who holds it.
 * <p>
 * {@code trustledger.scheduling.enabled=false} switches the cron triggers off (tests do this) while the lock and
 * the manual runs keep working.
 */
@Configuration(proxyBeanMethods = false)
public class SchedulingConfig {

    /** The ShedLock table, created by {@code V9__shared_create_shedlock.sql}. */
    public static final String LOCK_TABLE = "shared.shedlock";

    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName(LOCK_TABLE)
                .usingDbTime()
                .build());
    }

    @Bean
    LockingTaskExecutor lockingTaskExecutor(LockProvider lockProvider) {
        return new DefaultLockingTaskExecutor(lockProvider);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "trustledger.scheduling.enabled", havingValue = "true", matchIfMissing = true)
    static class CronTriggers {
    }
}
