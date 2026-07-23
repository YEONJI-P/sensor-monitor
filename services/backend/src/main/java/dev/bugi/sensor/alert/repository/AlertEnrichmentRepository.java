package dev.bugi.sensor.alert.repository;

import dev.bugi.sensor.alert.dto.EnrichmentClaim;
import dev.bugi.sensor.alert.entity.AlarmType;
import dev.bugi.sensor.device.entity.SensorChannel.ThresholdDirection;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * PostgreSQL SKIP LOCKED 기반 explain 보강 claim/lease 저장소.
 */
@Repository
@RequiredArgsConstructor
public class AlertEnrichmentRepository {

    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(30);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(30);

    private final NamedParameterJdbcTemplate jdbc;

    @Transactional
    public List<EnrichmentClaim> claim(int limit, Instant now) {
        if (limit <= 0) {
            return List.of();
        }
        UUID token = UUID.randomUUID();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", limit)
                .addValue("now", Timestamp.from(now))
                .addValue("leaseExpiredBefore", Timestamp.from(now.minus(LEASE)))
                .addValue("token", token);
        jdbc.update("""
                WITH candidates AS (
                    SELECT a.id
                    FROM alert a
                    JOIN alarm_episode e ON e.id = a.episode_id
                    WHERE a.enrichment_completed_at IS NULL
                      AND a.evidence IS NULL
                      AND e.evidence IS NULL
                      AND e.alarm_type IN ('THRESHOLD', 'DEVICE_SILENCE')
                      AND a.id = (
                          SELECT min(a2.id)
                          FROM alert a2
                          WHERE a2.episode_id = a.episode_id
                            AND a2.enrichment_completed_at IS NULL
                      )
                      AND (a.enrichment_next_attempt_at IS NULL
                           OR a.enrichment_next_attempt_at <= :now)
                      AND (a.enrichment_claimed_at IS NULL
                           OR a.enrichment_claimed_at < :leaseExpiredBefore)
                    ORDER BY a.created_at, a.id
                    FOR UPDATE OF a SKIP LOCKED
                    LIMIT :limit
                )
                UPDATE alert a
                SET enrichment_claimed_at = :now,
                    enrichment_lease_token = :token,
                    enrichment_attempts = a.enrichment_attempts + 1,
                    enrichment_error = NULL
                FROM candidates c
                WHERE a.id = c.id
                """, params);

        return jdbc.query("""
                SELECT a.id AS alert_id,
                       a.episode_id,
                       a.enrichment_lease_token,
                       a.enrichment_attempts,
                       e.alarm_type,
                       a.device_id,
                       a.channel_id,
                       COALESCE(e.snapshot ->> 'deviceName', d.name) AS device_name,
                       COALESCE(e.snapshot ->> 'quantityKind', c.quantity_kind) AS sensor_type,
                       COALESCE(e.snapshot ->> 'unit', c.unit) AS unit,
                       COALESCE(NULLIF(e.snapshot ->> 'sensorValue', '')::double precision,
                                a.sensor_value) AS sensor_value,
                       COALESCE(NULLIF(e.snapshot ->> 'thresholdValue', '')::double precision,
                                a.threshold_value) AS threshold_value,
                       COALESCE(e.snapshot ->> 'thresholdDirection',
                                c.threshold_direction) AS threshold_direction,
                       a.message,
                       COALESCE(NULLIF(e.snapshot ->> 'expectedIntervalSeconds', '')::integer,
                                d.expected_interval_seconds) AS expected_interval_seconds,
                       NULLIF(e.snapshot ->> 'lastSeenAt', '')::timestamptz AS last_seen_at,
                       NULLIF(e.snapshot ->> 'elapsedSeconds', '')::integer AS elapsed_seconds
                FROM alert a
                JOIN alarm_episode e ON e.id = a.episode_id
                LEFT JOIN device d ON d.id = a.device_id
                LEFT JOIN sensor_channel c ON c.id = a.channel_id
                WHERE a.enrichment_lease_token = :token
                ORDER BY a.created_at, a.id
                """, new MapSqlParameterSource("token", token),
                (rs, rowNum) -> mapClaim(rs));
    }

    @Transactional
    public boolean complete(EnrichmentClaim claim, String evidence, String recommendation,
                            Instant completedAt) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("alertId", claim.alertId())
                .addValue("episodeId", claim.episodeId())
                .addValue("token", claim.leaseToken())
                .addValue("evidence", evidence)
                .addValue("recommendation", recommendation)
                .addValue("completedAt", Timestamp.from(completedAt));
        int owned = jdbc.update("""
                UPDATE alert
                SET evidence = :evidence,
                    recommendation = :recommendation,
                    enrichment_completed_at = :completedAt,
                    enrichment_claimed_at = NULL,
                    enrichment_lease_token = NULL,
                    enrichment_next_attempt_at = NULL,
                    enrichment_error = NULL
                WHERE id = :alertId
                  AND enrichment_lease_token = :token
                """, params);
        if (owned == 0) {
            return false;
        }
        jdbc.update("""
                UPDATE alarm_episode
                SET evidence = :evidence,
                    recommendation = :recommendation,
                    updated_at = :completedAt
                WHERE id = :episodeId
                """, params);
        jdbc.update("""
                UPDATE alert
                SET evidence = :evidence,
                    recommendation = :recommendation,
                    enrichment_completed_at = :completedAt,
                    enrichment_claimed_at = NULL,
                    enrichment_lease_token = NULL,
                    enrichment_next_attempt_at = NULL,
                    enrichment_error = NULL
                WHERE episode_id = :episodeId
                  AND id <> :alertId
                  AND enrichment_completed_at IS NULL
                """, params);
        return true;
    }

    @Transactional
    public boolean fail(EnrichmentClaim claim, Instant failedAt, String error) {
        Duration backoff = backoff(claim.attempt());
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("alertId", claim.alertId())
                .addValue("token", claim.leaseToken())
                .addValue("nextAttemptAt", Timestamp.from(failedAt.plus(backoff)))
                .addValue("error", abbreviate(error));
        return jdbc.update("""
                UPDATE alert
                SET enrichment_claimed_at = NULL,
                    enrichment_lease_token = NULL,
                    enrichment_next_attempt_at = :nextAttemptAt,
                    enrichment_error = :error
                WHERE id = :alertId
                  AND enrichment_lease_token = :token
                """, params) == 1;
    }

    static Duration backoff(int attempt) {
        int shift = Math.max(0, Math.min(attempt - 1, 16));
        long seconds = Math.min(MAX_BACKOFF.toSeconds(),
                Math.multiplyExact(FIRST_BACKOFF.toSeconds(), 1L << shift));
        return Duration.ofSeconds(seconds);
    }

    private static EnrichmentClaim mapClaim(ResultSet rs) throws SQLException {
        String direction = rs.getString("threshold_direction");
        return new EnrichmentClaim(
                rs.getLong("alert_id"),
                rs.getLong("episode_id"),
                rs.getObject("enrichment_lease_token", UUID.class),
                rs.getInt("enrichment_attempts"),
                AlarmType.valueOf(rs.getString("alarm_type")),
                nullableLong(rs, "device_id"),
                nullableLong(rs, "channel_id"),
                rs.getString("device_name"),
                rs.getString("sensor_type"),
                rs.getString("unit"),
                nullableDouble(rs, "sensor_value"),
                nullableDouble(rs, "threshold_value"),
                direction == null ? null : ThresholdDirection.valueOf(direction),
                rs.getString("message"),
                nullableInteger(rs, "expected_interval_seconds"),
                rs.getTimestamp("last_seen_at") == null
                        ? null : rs.getTimestamp("last_seen_at").toInstant(),
                nullableInteger(rs, "elapsed_seconds"));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static String abbreviate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 1000 ? error : error.substring(0, 1000);
    }
}
