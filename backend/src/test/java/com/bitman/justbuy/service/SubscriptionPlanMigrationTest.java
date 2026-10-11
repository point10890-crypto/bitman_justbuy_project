package com.bitman.justbuy.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionPlanMigrationTest {
    @Test
    void migrationAddsNullablePlansWithoutChangingExistingMemberAccessOrHistory() throws Exception {
        String url = "jdbc:h2:mem:subscription-plans-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            try (var statement = connection.createStatement()) {
                statement.execute("""
                    CREATE TABLE users (id INT PRIMARY KEY, subscription VARCHAR(32),
                        subscription_end_date DATE, subscription_approved_at TIMESTAMP)
                    """);
                statement.execute("""
                    INSERT INTO users VALUES (1, 'PRO', DATE '2027-03-31', TIMESTAMP '2026-09-30 12:00:00'),
                        (2, 'PENDING', DATE '2027-01-31', TIMESTAMP '2026-10-01 12:00:00')
                    """);
            }
            Flyway.configure().dataSource(url, "sa", "").baselineOnMigrate(true)
                .locations("classpath:db/migration").load().migrate();
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("""
                     SELECT subscription, subscription_end_date, subscription_approved_at,
                         subscription_plan, requested_subscription_plan FROM users ORDER BY id
                     """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("subscription")).isEqualTo("PRO");
                assertThat(rows.getDate("subscription_end_date").toLocalDate()).isEqualTo(LocalDate.of(2027, 3, 31));
                assertThat(rows.getTimestamp("subscription_approved_at").toString()).isEqualTo("2026-09-30 12:00:00.0");
                assertThat(rows.getString("subscription_plan")).isNull();
                assertThat(rows.getString("requested_subscription_plan")).isNull();
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("subscription")).isEqualTo("PENDING");
                assertThat(rows.getDate("subscription_end_date").toLocalDate()).isEqualTo(LocalDate.of(2027, 1, 31));
                assertThat(rows.getString("subscription_plan")).isNull();
                assertThat(rows.getString("requested_subscription_plan")).isNull();
                assertThat(rows.next()).isFalse();
            }
        }
    }
}
