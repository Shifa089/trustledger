package com.trustledger.core.shared;

import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.core.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FlywaySchemasIT {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void createsOneSchemaPerModule() {
        List<String> schemas = jdbc.queryForList("select schema_name from information_schema.schemata", String.class);

        assertThat(schemas).contains("identity", "kyc", "wallet", "ledger", "payments", "audit", "privacy",
                "notification");
        assertThat(schemas).doesNotContain("aml"); // owned by risk-aml-service
    }

    @Test
    void recordsV1AsSuccessful() {
        Boolean success = jdbc.queryForObject(
                "select success from flyway_schema_history where version = '1'", Boolean.class);

        assertThat(success).isTrue();
    }
}
