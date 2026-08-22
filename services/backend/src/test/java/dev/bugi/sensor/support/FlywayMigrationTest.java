package dev.bugi.sensor.support;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 빈 PostgreSQL에 운영(prod) 설정을 그대로 적용한다.
 *
 * 컨텍스트가 뜨려면 전체 Flyway migration 적용 후 Hibernate ddl-auto=validate가 성공해야 한다.
 * 별도 SQL로 history, 핵심 스키마와 공개 데모 토폴로지를 확인해 테스트 설정이 우연히
 * create/update로 우회하지 않았는지도 막는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("prod")
@Testcontainers
class FlywayMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15")
                    .withDatabaseName("sensor_monitor")
                    .withUsername("sensor_monitor")
                    .withPassword("sensor_monitor");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    Environment environment;

    @Test
    void prod는_flyway_V1부터_V10을_적용하고_system_viewer_role을_준비한다() {
        assertThat(environment.getProperty("spring.flyway.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");

        var appliedVersions = jdbcTemplate.queryForList("""
                SELECT version FROM flyway_schema_history
                WHERE version IS NOT NULL AND success = true
                ORDER BY installed_rank
                """, String.class);
        assertThat(appliedVersions).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");

        // 정규화 batch 스키마: 관측 시각(observed_at)은 batch 가 timestamptz 로 가진다.
        String observedAtType = jdbcTemplate.queryForObject("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'measurement_batch'
                  AND column_name = 'observed_at'
                """, String.class);
        assertThat(observedAtType).isEqualTo("timestamp with time zone");

        // 구 scalar 텔레메트리 테이블은 제거됐다(호환 레이어 없음).
        assertThat(tableExists("sensor_data")).isFalse();
        assertThat(indexExists("sensor_data", "idx_sensor_device_recorded")).isFalse();

        // 새 정규화 테이블과 인덱스가 생성됐다.
        assertThat(tableExists("sensor_channel")).isTrue();
        assertThat(tableExists("measurement_batch")).isTrue();
        assertThat(tableExists("sensor_reading")).isTrue();
        assertThat(tableExists("channel_status")).isTrue();
        assertThat(indexExists("measurement_batch", "idx_measurement_batch_device_observed")).isTrue();
        assertThat(indexExists("sensor_reading", "idx_sensor_reading_channel")).isTrue();
        assertThat(indexExists("sensor_reading", "uk_sensor_reading_batch_channel")).isTrue();
        assertThat(indexExists("device", "uk_device_code")).isTrue();
        assertThat(indexExists("sensor_channel", "uk_sensor_channel_device_code")).isTrue();
        assertThat(tableExists("factory_operating_calendar")).isTrue();
        assertThat(tableExists("factory_weekly_interval")).isTrue();
        assertThat(tableExists("factory_date_override")).isTrue();
        assertThat(tableExists("factory_date_override_interval")).isTrue();
        assertThat(tableExists("alarm_episode")).isTrue();
        assertThat(tableExists("alarm_acknowledgement")).isTrue();
        assertThat(tableExists("ingest_receipt")).isTrue();
        assertThat(indexExists("factory_weekly_interval", "idx_factory_weekly_interval_factory_day")).isTrue();
        assertThat(indexExists("factory_date_override", "uk_factory_date_override")).isTrue();
        // 채널 화면 알림 조회(channel_id 필터 + created_at 정렬)용 인덱스.
        assertThat(indexExists("alert", "idx_alert_channel_created")).isTrue();
        assertThat(indexExists("alarm_episode", "uk_alarm_episode_open_threshold")).isTrue();
        assertThat(indexExists("alarm_episode", "uk_alarm_episode_open_device_silence")).isTrue();
        assertThat(indexExists("alarm_episode", "uk_alarm_episode_open_zone_silence")).isTrue();
        assertThat(indexExists("ingest_receipt", "uk_ingest_receipt_device_event")).isTrue();
        assertThat(indexExists("alert", "idx_alert_enrichment_claim")).isTrue();

        // device 는 설정만 남겼다: type/threshold_value 제거, code 추가.
        assertThat(columnExists("device", "type")).isFalse();
        assertThat(columnExists("device", "threshold_value")).isFalse();
        assertThat(columnExists("device", "code")).isTrue();
        // threshold mirror는 channel_status, freshness heartbeat는 device_status 경계에 남는다.
        assertThat(columnExists("device_status", "in_alarm")).isFalse();
        assertThat(columnExists("device_status", "last_alert_at")).isFalse();
        assertThat(columnExists("device_status", "last_seen_at")).isTrue();
        assertThat(columnExists("channel_status", "active_episode_id")).isTrue();
        assertThat(columnExists("channel_status", "last_evaluated_observed_at")).isTrue();
        assertThat(columnExists("channel_status", "last_evaluated_batch_id")).isTrue();
        // alert 앵커: channel_id·batch_id 추가.
        assertThat(columnExists("alert", "channel_id")).isTrue();
        assertThat(columnExists("alert", "batch_id")).isTrue();
        assertThat(columnExists("alert", "episode_id")).isTrue();
        assertThat(columnExists("alert", "alarm_type")).isTrue();
        assertThat(columnExists("alert", "scope_type")).isTrue();
        assertThat(columnExists("alert", "notification_reason")).isTrue();
        assertThat(columnExists("alert", "enrichment_lease_token")).isTrue();
        assertThat(columnExists("alert", "enrichment_claimed_at")).isTrue();
        assertThat(columnExists("alert", "enrichment_next_attempt_at")).isTrue();
        assertThat(columnExists("alert", "enrichment_completed_at")).isTrue();
        assertThat(columnExists("measurement_batch", "receipt_id")).isTrue();
        // failed_reading: 문자열 식별자 추가.
        assertThat(columnExists("failed_reading", "device_code")).isTrue();
        assertThat(columnExists("failed_reading", "channel_code")).isTrue();

        assertThat(rowCount("factories")).isEqualTo(2);
        assertThat(rowCount("zones")).isEqualTo(3);
        assertThat(rowCount("device")).isEqualTo(3);
        assertThat(rowCount("sensor_channel")).isEqualTo(20);
        assertThat(rowCount("device_status")).isEqualTo(3);
        assertThat(rowCount("channel_status")).isEqualTo(20);
        assertThat(rowCount("users")).isZero();
        assertThat(rowCount("zone_users")).isZero();
        assertThat(rowCount("factory_operating_calendar")).isEqualTo(2);
        assertThat(rowCount("factory_weekly_interval")).isEqualTo(10);

        jdbcTemplate.update("""
                INSERT INTO users (employee_id, name, role, status)
                VALUES ('MIGRATION-DEMO', 'migration demo', 'SYSTEM_VIEWER', 'ACTIVE')
                """);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM users
                WHERE employee_id = 'MIGRATION-DEMO' AND role = 'SYSTEM_VIEWER'
                """, Integer.class)).isOne();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO users (employee_id, name, role, status)
                VALUES ('INVALID-ROLE', 'invalid role', 'UNKNOWN', 'ACTIVE')
                """)).hasMessageContaining("ck_users_role");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM factories f
                LEFT JOIN factory_operating_calendar c ON c.factory_id = f.id
                WHERE c.factory_id IS NULL
                """, Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM factory_operating_calendar
                WHERE timezone_id = 'Asia/Seoul' AND resume_grace_seconds = 300 AND revision = 0
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM factory_weekly_interval
                WHERE iso_day BETWEEN 1 AND 5 AND start_minute = 480 AND end_minute = 1080
                """, Integer.class)).isEqualTo(10);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM factory_weekly_interval WHERE iso_day IN (6, 7)
                """, Integer.class)).isZero();

        assertThat(columnType("factory_operating_calendar", "created_at"))
                .isEqualTo("timestamp with time zone");
        assertThat(columnType("factory_date_override", "local_date")).isEqualTo("date");
        assertThat(columnType("factory_weekly_interval", "start_minute")).isEqualTo("smallint");
        assertThat(columnType("alarm_episode", "opened_at")).isEqualTo("timestamp with time zone");
        assertThat(columnType("alarm_episode", "snapshot")).isEqualTo("jsonb");
        assertThat(columnType("ingest_receipt", "response_json")).isEqualTo("jsonb");
        assertThat(columnType("alert", "enrichment_claimed_at"))
                .isEqualTo("timestamp with time zone");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE factory_operating_calendar SET resume_grace_seconds = 86401
                WHERE factory_id = (SELECT min(id) FROM factories)
                """)).hasMessageContaining("ck_factory_operating_calendar_grace");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO factory_weekly_interval (factory_id, iso_day, start_minute, end_minute)
                VALUES ((SELECT min(id) FROM factories), 8, 0, 60)
                """)).hasMessageContaining("ck_factory_weekly_interval_day");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO factory_date_override (factory_id, local_date, kind)
                VALUES ((SELECT min(id) FROM factories), DATE '2026-07-20', 'HOLIDAY')
                """)).hasMessageContaining("ck_factory_date_override_kind");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO alarm_episode (
                    alarm_type, scope_type, status, current_severity, max_severity,
                    device_id, channel_id, opened_at, snapshot, created_at, updated_at
                ) VALUES (
                    'THRESHOLD', 'DEVICE', 'OPEN', 'WARNING', 'WARNING',
                    (SELECT min(id) FROM device), NULL, CURRENT_TIMESTAMP, '{}'::jsonb,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """)).hasMessageContaining("ck_alarm_episode_scope");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO alarm_episode (
                    alarm_type, scope_type, status, current_severity, max_severity,
                    device_id, channel_id, opened_at, snapshot, created_at, updated_at
                ) VALUES (
                    'THRESHOLD', 'CHANNEL', 'OPEN', 'WARNING', 'WARNING',
                    (SELECT d.id FROM device d
                     WHERE d.id <> (SELECT c.device_id FROM sensor_channel c ORDER BY c.id LIMIT 1)
                     ORDER BY d.id LIMIT 1),
                    (SELECT c.id FROM sensor_channel c ORDER BY c.id LIMIT 1),
                    CURRENT_TIMESTAMP, '{}'::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """)).hasMessageContaining("fk_alarm_episode_threshold_owner");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO alert (
                    alarm_type, scope_type, notification_reason,
                    message, severity, created_at, updated_at
                ) VALUES (
                    'THRESHOLD', 'DEVICE', 'LEGACY',
                    'invalid pair', 'WARNING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """)).hasMessageContaining("ck_alert_alarm_scope_pair");

        // V7을 모르는 직전 backend가 lifecycle metadata를 생략해도 rollback 중 INSERT는 유지된다.
        jdbcTemplate.update("""
                INSERT INTO alert (
                    device_id, message, severity, created_at, updated_at
                ) VALUES (
                    (SELECT min(id) FROM device),
                    '데이터 수신 끊김 - legacy rollback',
                    'WARNING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM alert
                WHERE message = '데이터 수신 끊김 - legacy rollback'
                  AND alarm_type = 'DEVICE_SILENCE'
                  AND scope_type = 'DEVICE'
                  AND notification_reason = 'LEGACY'
                """, Integer.class)).isOne();

        jdbcTemplate.update("""
                INSERT INTO alarm_episode (
                    alarm_type, scope_type, status, current_severity, max_severity,
                    device_id, channel_id, opened_at, snapshot, created_at, updated_at
                ) VALUES (
                    'THRESHOLD', 'CHANNEL', 'OPEN', 'WARNING', 'WARNING',
                    (SELECT c.device_id FROM sensor_channel c ORDER BY c.id LIMIT 1),
                    (SELECT c.id FROM sensor_channel c ORDER BY c.id LIMIT 1),
                    CURRENT_TIMESTAMP, '{}'::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO alarm_episode (
                    alarm_type, scope_type, status, current_severity, max_severity,
                    device_id, channel_id, opened_at, snapshot, created_at, updated_at
                ) VALUES (
                    'THRESHOLD', 'CHANNEL', 'OPEN', 'CRITICAL', 'CRITICAL',
                    (SELECT c.device_id FROM sensor_channel c ORDER BY c.id LIMIT 1),
                    (SELECT c.id FROM sensor_channel c ORDER BY c.id LIMIT 1),
                    CURRENT_TIMESTAMP, '{}'::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """)).hasMessageContaining("uk_alarm_episode_open_threshold");

        jdbcTemplate.update("""
                INSERT INTO ingest_receipt (
                    device_code, event_id, request_hash, created_at
                ) VALUES ('CMAPSS-U1', 'evt-1', 'hash-1', CURRENT_TIMESTAMP)
                """);
        jdbcTemplate.update("""
                INSERT INTO ingest_receipt (
                    device_code, event_id, request_hash, created_at
                ) VALUES ('CMAPSS-U2', 'evt-1', 'hash-2', CURRENT_TIMESTAMP)
                """);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO ingest_receipt (
                    device_code, event_id, request_hash, created_at
                ) VALUES ('CMAPSS-U1', 'evt-1', 'hash-3', CURRENT_TIMESTAMP)
                """)).hasMessageContaining("uk_ingest_receipt_device_event");

        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM zones z
                JOIN factories f ON f.id = z.factory_id
                WHERE (f.name = '엔진시험동' AND z.name IN ('엔진1구역', '엔진2구역'))
                   OR (f.name = '가공동' AND z.name = '밀링1구역')
                """, Integer.class)).isEqualTo(3);

        // 물리 Device 3개(code 기준).
        assertThat(jdbcTemplate.queryForList(
                "SELECT code FROM device ORDER BY id", String.class))
                .containsExactly("CMAPSS-U1", "CMAPSS-U2", "CNC-EXP01");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM device WHERE expected_interval_seconds = 10", Integer.class))
                .isEqualTo(3);

        // 채널 배치: device code → 채널 code 집합.
        assertThat(channelCodes("CMAPSS-U1"))
                .containsExactlyInAnyOrder("s2", "s4", "s7", "s11", "s15", "s21");
        assertThat(channelCodes("CMAPSS-U2"))
                .containsExactlyInAnyOrder("s2", "s4", "s7", "s11", "s15", "s21");
        assertThat(channelCodes("CNC-EXP01"))
                .containsExactlyInAnyOrder(
                        "S1_OutputPower", "S1_CurrentFeedback",
                        "X1_ActualAcceleration", "Y1_ActualAcceleration", "Z1_ActualAcceleration",
                        "X1_CurrentFeedback", "S1_ActualVelocity", "M1_CURRENT_FEEDRATE");

        // 채널 임계값(device.channel 조합 → threshold).
        assertThat(channelThresholds()).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("CMAPSS-U1|s2", 643.4),
                Map.entry("CMAPSS-U1|s4", 1416.0),
                Map.entry("CMAPSS-U1|s7", 552.4),
                Map.entry("CMAPSS-U1|s11", 47.8),
                Map.entry("CMAPSS-U1|s15", 8.485),
                Map.entry("CMAPSS-U1|s21", 23.165),
                Map.entry("CMAPSS-U2|s2", 643.4),
                Map.entry("CMAPSS-U2|s4", 1416.0),
                Map.entry("CMAPSS-U2|s7", 552.4),
                Map.entry("CMAPSS-U2|s11", 47.8),
                Map.entry("CMAPSS-U2|s15", 8.485),
                Map.entry("CMAPSS-U2|s21", 23.165),
                Map.entry("CNC-EXP01|S1_OutputPower", 0.25),
                Map.entry("CNC-EXP01|S1_CurrentFeedback", 30.0),
                Map.entry("CNC-EXP01|X1_ActualAcceleration", 800.0),
                Map.entry("CNC-EXP01|Y1_ActualAcceleration", 500.0),
                Map.entry("CNC-EXP01|Z1_ActualAcceleration", 1000.0),
                Map.entry("CNC-EXP01|X1_CurrentFeedback", 14.0)
        ));

        assertThat(directionCounts()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "ABOVE", 10,
                "BELOW", 4,
                "ABS_ABOVE", 4
        ));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sensor_channel WHERE threshold_value IS NULL AND threshold_direction IS NULL",
                Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList("""
                SELECT code FROM sensor_channel
                WHERE threshold_value IS NULL AND threshold_direction IS NULL AND unit IS NULL
                ORDER BY code
                """, String.class))
                .containsExactly("M1_CURRENT_FEEDRATE", "S1_ActualVelocity");

        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_constraint
                WHERE conname = 'ck_sensor_channel_threshold_pair'
                """, Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO sensor_channel (
                    device_id, code, threshold_value, threshold_direction, created_at, updated_at
                ) VALUES ((SELECT id FROM device WHERE code = 'CMAPSS-U1'),
                          'INVALID-PAIR', 1.0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """)).hasMessageContaining("ck_sensor_channel_threshold_pair");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO sensor_channel (
                    device_id, code, threshold_value, threshold_direction, created_at, updated_at
                ) VALUES ((SELECT id FROM device WHERE code = 'CMAPSS-U1'),
                          'INVALID-ABS', -1.0, 'ABS_ABOVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """)).hasMessageContaining("ck_sensor_channel_threshold_pair");
    }

    private int rowCount(String tableName) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + tableName, Integer.class);
    }

    private boolean tableExists(String table) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = ?
                """, Integer.class, table) > 0;
    }

    private boolean columnExists(String table, String column) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """, Integer.class, table, column) > 0;
    }

    private boolean indexExists(String table, String index) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = ? AND indexname = ?
                """, Integer.class, table, index) > 0;
    }

    private String columnType(String table, String column) {
        return jdbcTemplate.queryForObject("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """, String.class, table, column);
    }

    private java.util.List<String> channelCodes(String deviceCode) {
        return jdbcTemplate.queryForList("""
                SELECT c.code FROM sensor_channel c
                JOIN device d ON d.id = c.device_id
                WHERE d.code = ?
                """, String.class, deviceCode);
    }

    private Map<String, Double> channelThresholds() {
        return jdbcTemplate.query("""
                SELECT d.code AS device_code, c.code AS channel_code, c.threshold_value
                FROM sensor_channel c JOIN device d ON d.id = c.device_id
                WHERE c.threshold_value IS NOT NULL
                """, resultSet -> {
            Map<String, Double> thresholds = new HashMap<>();
            while (resultSet.next()) {
                thresholds.put(
                        resultSet.getString("device_code") + "|" + resultSet.getString("channel_code"),
                        resultSet.getDouble("threshold_value"));
            }
            return thresholds;
        });
    }

    private Map<String, Integer> directionCounts() {
        return jdbcTemplate.query("""
                SELECT threshold_direction, count(*) AS channel_count
                FROM sensor_channel
                WHERE threshold_direction IS NOT NULL
                GROUP BY threshold_direction
                """, resultSet -> {
            Map<String, Integer> counts = new HashMap<>();
            while (resultSet.next()) {
                counts.put(resultSet.getString("threshold_direction"), resultSet.getInt("channel_count"));
            }
            return counts;
        });
    }
}
