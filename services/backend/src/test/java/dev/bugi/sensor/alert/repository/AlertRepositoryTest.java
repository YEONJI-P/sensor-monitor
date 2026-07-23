package dev.bugi.sensor.alert.repository;

import dev.bugi.sensor.device.entity.Device;
import dev.bugi.sensor.device.entity.SensorChannel;
import dev.bugi.sensor.device.entity.SensorChannel.ThresholdDirection;
import dev.bugi.sensor.factory.entity.Zone;
import dev.bugi.sensor.support.AbstractPostgresTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AlertRepository 의 두 채널 기반 네이티브 쿼리를 실제 Postgres 로 검증한다.
 * mock 으로는 불가능한 것들: LIMIT 바인딩, 엔티티 매핑, to_char/timestamptz 타임존 변환.
 */
class AlertRepositoryTest extends AbstractPostgresTest {

    @Autowired
    AlertRepository alertRepository;

    @PersistenceContext
    EntityManager em;

    private Device persistDevice() {
        Zone zone = persistZone(persistFactory("F1"), "Z1");
        return tem.persist(Device.builder()
                .zone(zone).code("D-" + UUID.randomUUID()).name("D1")
                .location("L1").expectedIntervalSeconds(60).build());
    }

    private SensorChannel persistChannel(Device device) {
        return tem.persist(SensorChannel.builder()
                .device(device).code("s4").unit("°R").quantityKind("temperature")
                .thresholdValue(80.0).thresholdDirection(ThresholdDirection.ABOVE).build());
    }

    /** created_at 을 명시적으로 지정해 알림 1건을 네이티브 삽입한다(감사 Clock 우회). */
    private void insertAlert(Long channelId, Long deviceId, String message, String severity,
                             String evidence, String recommendation, Instant createdAt) {
        em.createNativeQuery("""
                INSERT INTO alert (device_id, channel_id, sensor_value, threshold_value, message,
                                   severity, evidence, recommendation, created_at, updated_at,
                                   alarm_type, scope_type, notification_reason,
                                   notification_status, notification_attempts)
                VALUES (:deviceId, :channelId, 99.0, 80.0, :message, :severity, :evidence,
                        :recommendation, :createdAt, :createdAt,
                        'THRESHOLD', 'CHANNEL', 'LEGACY', 'NOT_REQUESTED', 0)
                """)
                .setParameter("deviceId", deviceId)
                .setParameter("channelId", channelId)
                .setParameter("message", message)
                .setParameter("severity", severity)
                .setParameter("evidence", evidence)
                .setParameter("recommendation", recommendation)
                .setParameter("createdAt", createdAt)
                .executeUpdate();
    }

    @Test
    void findRecentByChannelId_limit과_정렬과_신규컬럼_매핑을_검증() {
        Device device = persistDevice();
        SensorChannel channel = persistChannel(device);
        SensorChannel other = persistChannel(persistDevice());

        Instant base = Instant.parse("2026-01-10T00:00:00Z");
        insertAlert(channel.getId(), device.getId(), "old", "WARNING", null, null, base);
        insertAlert(channel.getId(), device.getId(), "mid", "CRITICAL", "ev-mid", "rec-mid", base.plusSeconds(60));
        insertAlert(channel.getId(), device.getId(), "new", "CRITICAL", "ev-new", "rec-new", base.plusSeconds(120));
        insertAlert(other.getId(), other.getDevice().getId(), "otherchan", "WARNING", null, null, base.plusSeconds(200));
        em.flush();
        em.clear();

        List<dev.bugi.sensor.alert.entity.Alert> recent =
                alertRepository.findRecentByChannelId(channel.getId(), 2);

        // LIMIT 2 + created_at DESC → [new, mid], 다른 채널은 제외
        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).getMessage()).isEqualTo("new");
        assertThat(recent.get(1).getMessage()).isEqualTo("mid");
        // 엔티티 매핑(severity/evidence/recommendation 및 신규 channel FK)
        assertThat(recent.get(0).getSeverity()).isEqualTo(dev.bugi.sensor.alert.entity.AlertSeverity.CRITICAL);
        assertThat(recent.get(0).getEvidence()).isEqualTo("ev-new");
        assertThat(recent.get(0).getRecommendation()).isEqualTo("rec-new");
        assertThat(recent.get(0).getChannel().getId()).isEqualTo(channel.getId());
    }

    @Test
    void findDailyCountByChannelId_to_char는_세션_타임존으로_timestamptz를_변환해_날짜를_가른다() {
        Device device = persistDevice();
        SensorChannel channel = persistChannel(device);

        Instant beforeMidnightUtc = Instant.parse("2026-01-01T23:30:00Z"); // KST(+9)로는 01-02 08:30
        Instant afterMidnightUtc = Instant.parse("2026-01-02T00:30:00Z");  // KST(+9)로는 01-02 09:30
        insertAlert(channel.getId(), device.getId(), "a", "WARNING", null, null, beforeMidnightUtc);
        insertAlert(channel.getId(), device.getId(), "b", "WARNING", null, null, afterMidnightUtc);
        em.flush();

        Instant startDate = Instant.parse("2026-01-01T00:00:00Z");

        em.createNativeQuery("SET TIME ZONE 'UTC'").executeUpdate();
        Map<String, Long> utcBuckets = toBuckets(alertRepository.findDailyCountByChannelId(channel.getId(), startDate));
        assertThat(utcBuckets).containsOnlyKeys("2026-01-01", "2026-01-02");
        assertThat(utcBuckets.get("2026-01-01")).isEqualTo(1L);
        assertThat(utcBuckets.get("2026-01-02")).isEqualTo(1L);

        em.createNativeQuery("SET TIME ZONE 'Asia/Seoul'").executeUpdate();
        Map<String, Long> kstBuckets = toBuckets(alertRepository.findDailyCountByChannelId(channel.getId(), startDate));
        assertThat(kstBuckets).containsOnlyKeys("2026-01-02");
        assertThat(kstBuckets.get("2026-01-02")).isEqualTo(2L);
    }

    private Map<String, Long> toBuckets(List<Object[]> rows) {
        return rows.stream().collect(Collectors.toMap(
                r -> (String) r[0],
                r -> ((Number) r[1]).longValue()));
    }

    @Test
    void 존재하지_않는_channel_id를_참조하면_FK위반으로_거부된다() {
        Device device = persistDevice();
        SensorChannel bogusChannelRef = em.getReference(SensorChannel.class, -1L);

        dev.bugi.sensor.alert.entity.Alert alert = dev.bugi.sensor.alert.entity.Alert.builder()
                .device(device)
                .channel(bogusChannelRef)
                .message("존재하지 않는 채널 참조")
                .severity(dev.bugi.sensor.alert.entity.AlertSeverity.WARNING)
                .build();

        assertThatThrownBy(() -> {
            alertRepository.save(alert);
            em.flush();
        }).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
