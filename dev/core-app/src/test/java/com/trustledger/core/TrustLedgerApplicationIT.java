package com.trustledger.core;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TrustLedgerApplicationIT {

    @Autowired
    DataSource dataSource;

    @Autowired
    RedisConnectionFactory redisConnectionFactory;

    @Autowired
    Environment environment;

    @Test
    void contextLoadsAndReachesPostgresAndRedis() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }
        try (var redis = redisConnectionFactory.getConnection()) {
            assertThat(redis.ping()).isEqualTo("PONG");
        }
    }

    @Test
    void nonLocalProfilesLogStructuredEcsJson() {
        assertThat(environment.getProperty("logging.structured.format.console")).isEqualTo("ecs");
    }
}
