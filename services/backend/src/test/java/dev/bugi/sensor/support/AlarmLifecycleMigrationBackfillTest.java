package dev.bugi.sensor.support;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V7 적용 직전의 실제 상태를 만들어 legacy in_alarm=true 승격과 status backfill을 검증한다.
 */
class AlarmLifecycleMigrationBackfillTest {

    @Test
    void V7은_true_channel_mirror만_OPEN_episode로_승격하고_모든_status행을_backfill한다() throws Exception {
        var postgres = AbstractPostgresTest.POSTGRES;
        String schema = "alarm_backfill_" + UUID.randomUUID().toString().replace("-", "");

        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .target(MigrationVersion.fromVersion("6"))
                .load()
                .migrate();

        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema);
            statement.executeUpdate("""
                    INSERT INTO channel_status (channel_id, in_alarm, last_alert_at)
                    SELECT min(id), true, TIMESTAMPTZ '2026-07-23 01:00:00Z'
                    FROM sensor_channel
                    """);
            statement.executeUpdate("""
                    INSERT INTO measurement_batch (
                        observed_at, received_at, source_seq, device_id
                    )
                    SELECT TIMESTAMPTZ '2026-07-23 00:59:00Z',
                           TIMESTAMPTZ '2026-07-23 01:00:00Z',
                           1,
                           device_id
                    FROM sensor_channel
                    ORDER BY id
                    LIMIT 1
                    """);
            statement.executeUpdate("""
                    INSERT INTO sensor_reading (value, batch_id, channel_id)
                    SELECT 1.0, mb.id, sc.id
                    FROM measurement_batch mb
                    JOIN sensor_channel sc ON sc.device_id = mb.device_id
                    ORDER BY mb.id DESC, sc.id
                    LIMIT 1
                    """);
        }

        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .load()
                .migrate();

        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema);
            try (var result = statement.executeQuery("""
                    SELECT count(*) AS episode_count,
                           count(*) FILTER (WHERE legacy_backfill) AS legacy_count
                    FROM alarm_episode
                    WHERE status = 'OPEN' AND alarm_type = 'THRESHOLD'
                    """)) {
                result.next();
                assertThat(result.getInt("episode_count")).isOne();
                assertThat(result.getInt("legacy_count")).isOne();
            }
            try (var result = statement.executeQuery("""
                    SELECT
                        (SELECT count(*) FROM device_status) AS device_status_count,
                        (SELECT count(*) FROM channel_status) AS channel_status_count,
                        (SELECT count(*) FROM device) AS device_count,
                        (SELECT count(*) FROM sensor_channel) AS channel_count,
                        (SELECT count(*) FROM channel_status
                         WHERE in_alarm AND active_episode_id IS NOT NULL) AS mirrored_count,
                        (SELECT count(*) FROM channel_status
                         WHERE last_evaluated_observed_at =
                                   TIMESTAMPTZ '2026-07-23 00:59:00Z'
                           AND last_evaluated_batch_id IS NOT NULL) AS cursor_count
                    """)) {
                result.next();
                assertThat(result.getInt("device_status_count")).isEqualTo(result.getInt("device_count"));
                assertThat(result.getInt("channel_status_count")).isEqualTo(result.getInt("channel_count"));
                assertThat(result.getInt("mirrored_count")).isOne();
                assertThat(result.getInt("cursor_count")).isOne();
            }
        }
    }
}
