package dev.bugi.sensor.device.scheduler;

import dev.bugi.sensor.alert.entity.AlarmEpisode;
import dev.bugi.sensor.alert.entity.AlarmEpisodeStatus;
import dev.bugi.sensor.alert.entity.AlarmResolutionReason;
import dev.bugi.sensor.alert.entity.AlarmType;
import dev.bugi.sensor.alert.entity.AlertNotificationReason;
import dev.bugi.sensor.alert.entity.AlertSeverity;
import dev.bugi.sensor.alert.repository.AlarmEpisodeRepository;
import dev.bugi.sensor.alert.repository.AlertRepository;
import dev.bugi.sensor.alert.service.AlarmLifecycleService;
import dev.bugi.sensor.alert.service.AlarmNotificationFactory;
import dev.bugi.sensor.device.entity.Device;
import dev.bugi.sensor.device.entity.DeviceStatus;
import dev.bugi.sensor.device.repository.DeviceRepository;
import dev.bugi.sensor.device.repository.DeviceStatusRepository;
import dev.bugi.sensor.factory.calendar.service.OperatingCalendarService;
import dev.bugi.sensor.factory.calendar.service.OperatingCalendarService.OperatingDecision;
import dev.bugi.sensor.factory.entity.Factory;
import dev.bugi.sensor.factory.entity.Zone;
import dev.bugi.sensor.factory.repository.FactoryRepository;
import dev.bugi.sensor.factory.repository.ZoneRepository;
import dev.bugi.sensor.support.AbstractPostgresTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.when;

@Import({
        AlarmLifecycleService.class,
        AlarmNotificationFactory.class,
        FreshnessScheduler.class,
        FreshnessSchedulerPostgresTest.MutableClockConfig.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FreshnessSchedulerPostgresTest extends AbstractPostgresTest {

    private static final Instant START = Instant.parse("2026-07-23T04:00:00Z");
    private static final OperatingDecision ACTIVE =
            new OperatingDecision(true, false, START.minusSeconds(3600), "SCHEDULED_ACTIVE");

    @Autowired FreshnessScheduler scheduler;
    @Autowired AlarmLifecycleService lifecycleService;
    @Autowired MutableClock clock;
    @Autowired FactoryRepository factoryRepository;
    @Autowired ZoneRepository zoneRepository;
    @Autowired DeviceRepository deviceRepository;
    @Autowired DeviceStatusRepository statusRepository;
    @Autowired AlarmEpisodeRepository episodeRepository;
    @Autowired AlertRepository alertRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    @MockitoBean OperatingCalendarService operatingCalendarService;

    @BeforeEach
    void setUp() {
        clock.set(START);
        when(operatingCalendarService.evaluate(anySet(), any()))
                .thenReturn(Map.of(1L, ACTIVE));
        when(operatingCalendarService.evaluateAlwaysOpenFallback()).thenReturn(ACTIVE);
    }

    @AfterEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE TABLE factories RESTART IDENTITY CASCADE");
    }

    @Test
    void 다중_scheduler와_재시작에도_같은_episode_initial은_정확히_한건이다() {
        Fixture fixture = fixture("concurrent", START.minusSeconds(61));

        CompletableFuture<Void> first =
                CompletableFuture.runAsync(scheduler::checkFreshness);
        CompletableFuture<Void> second =
                CompletableFuture.runAsync(scheduler::checkFreshness);
        CompletableFuture.allOf(first, second).join();
        // 메모리 debounce 없이 다시 실행하는 것이 재시작 후 첫 tick과 같은 DB 경계다.
        scheduler.checkFreshness();

        assertThat(openEpisodes(AlarmType.DEVICE_SILENCE)).hasSize(1);
        assertThat(alertRepository.findAll()).singleElement().satisfies(alert -> {
            assertThat(alert.getSeverity()).isEqualTo(AlertSeverity.CRITICAL);
            assertThat(alert.getNotificationReason()).isEqualTo(AlertNotificationReason.INITIAL);
            assertThat(alert.getDevice().getId()).isEqualTo(fixture.first().getId());
        });
    }

    @Test
    void eligible_두대_전부_stale이면_zone_CRITICAL이고_부분회복은_reclassify된다() {
        Fixture fixture = fixture("cohort", START.minusSeconds(61), START.minusSeconds(70));

        scheduler.checkFreshness();

        assertThat(openEpisodes(AlarmType.ZONE_SILENCE)).singleElement()
                .satisfies(episode ->
                        assertThat(episode.getCurrentSeverity()).isEqualTo(AlertSeverity.CRITICAL));
        assertThat(alertRepository.findAll()).hasSize(1);

        markSeen(fixture.second(), START);
        scheduler.checkFreshness();

        AlarmEpisode resolvedZone = episodes(AlarmType.ZONE_SILENCE).get(0);
        assertThat(resolvedZone.getStatus()).isEqualTo(AlarmEpisodeStatus.RESOLVED);
        assertThat(resolvedZone.getResolutionReason())
                .isEqualTo(AlarmResolutionReason.RECLASSIFIED);
        assertThat(openEpisodes(AlarmType.DEVICE_SILENCE)).hasSize(1);
        assertThat(alertRepository.findAll()).hasSize(2);

        markSeen(fixture.first(), START);
        scheduler.checkFreshness();
        AlarmEpisode resolvedDevice = episodes(AlarmType.DEVICE_SILENCE).get(0);
        assertThat(resolvedDevice.getStatus()).isEqualTo(AlarmEpisodeStatus.RESOLVED);
        assertThat(resolvedDevice.getResolutionReason())
                .isEqualTo(AlarmResolutionReason.RECOVERED);
    }

    @Test
    void never_seen은_cohort_분모와_episode에서_제외된다() {
        Fixture fixture = fixture("never-seen", START.minusSeconds(61), null);

        scheduler.checkFreshness();

        assertThat(openEpisodes(AlarmType.ZONE_SILENCE)).isEmpty();
        assertThat(openEpisodes(AlarmType.DEVICE_SILENCE)).singleElement()
                .satisfies(episode ->
                        assertThat(episode.getDevice().getId()).isEqualTo(fixture.first().getId()));
        assertThat(alertRepository.findAll()).hasSize(1);
    }

    @Test
    void calendar가_episode를_PLANNED_OFFLINE과_RESUME_GRACE로_종료한다() {
        fixture("calendar", START.minusSeconds(61));
        scheduler.checkFreshness();

        when(operatingCalendarService.evaluate(anySet(), any())).thenReturn(Map.of(
                1L, new OperatingDecision(false, false, null, "PLANNED_OFFLINE")));
        scheduler.checkFreshness();
        assertThat(episodes(AlarmType.DEVICE_SILENCE).get(0).getResolutionReason())
                .isEqualTo(AlarmResolutionReason.PLANNED_OFFLINE);

        when(operatingCalendarService.evaluate(anySet(), any()))
                .thenReturn(Map.of(1L, ACTIVE));
        scheduler.checkFreshness();
        when(operatingCalendarService.evaluate(anySet(), any())).thenReturn(Map.of(
                1L, new OperatingDecision(true, true, START.minusSeconds(10), "RESUME_GRACE")));
        scheduler.checkFreshness();

        assertThat(episodes(AlarmType.DEVICE_SILENCE)).hasSize(2);
        assertThat(episodes(AlarmType.DEVICE_SILENCE).get(1).getResolutionReason())
                .isEqualTo(AlarmResolutionReason.RESUME_GRACE);
    }

    @Test
    void 다섯분_reminder는_ack가_현재_severity를_확인하면_억제된다() {
        Fixture fixture = fixture("reminder", START.minusSeconds(61));
        scheduler.checkFreshness();
        clock.set(START.plusSeconds(299));
        scheduler.checkFreshness();
        assertThat(alertRepository.findAll()).hasSize(1);

        clock.set(START.plusSeconds(300));
        scheduler.checkFreshness();
        assertThat(alertRepository.findAll()).hasSize(2);
        assertThat(alertRepository.findAll().get(1).getNotificationReason())
                .isEqualTo(AlertNotificationReason.REMINDER);

        // ack 자체의 role/공장 계약은 Wave 1 서비스 테스트에서 검증한다. 여기서는 현재 severity
        // ack 이후 reminder 억제만 실제 PostgreSQL에서 고정한다.
        Long userId = insertUser(fixture.factory());
        AlarmEpisode episode = openEpisodes(AlarmType.DEVICE_SILENCE).get(0);
        lifecycleService.acknowledge(episode.getId(), userId);

        clock.set(START.plusSeconds(600));
        scheduler.checkFreshness();
        assertThat(alertRepository.findAll()).hasSize(2);
    }

    @Test
    void ack후_severity가_내려갔다_재상승하면_escalation과_새_ack경계가_적용된다() {
        Fixture fixture = fixture("escalation", START.minusSeconds(61));
        clock.set(START.minusSeconds(20));
        AlarmEpisode episode = lifecycleService.openDeviceSilence(
                fixture.first().getId(), AlertSeverity.CRITICAL,
                Map.of("expectedIntervalSeconds", 30));
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            AlarmEpisode managed = episodeRepository.findById(episode.getId()).orElseThrow();
            managed.markNotified(START.minusSeconds(20));
        });
        clock.set(START.minusSeconds(10));
        lifecycleService.acknowledge(episode.getId(), insertUser(fixture.factory()));
        lifecycleService.openDeviceSilenceResult(
                fixture.first().getId(), AlertSeverity.WARNING,
                Map.of("expectedIntervalSeconds", 30));

        clock.set(START);
        scheduler.checkFreshness();

        assertThat(alertRepository.findAll()).singleElement().satisfies(alert -> {
            assertThat(alert.getSeverity()).isEqualTo(AlertSeverity.CRITICAL);
            assertThat(alert.getNotificationReason())
                    .isEqualTo(AlertNotificationReason.SEVERITY_ESCALATION);
        });

        clock.set(START.plusSeconds(300));
        scheduler.checkFreshness();
        assertThat(alertRepository.findAll()).hasSize(2);
        assertThat(alertRepository.findAll().get(1).getNotificationReason())
                .isEqualTo(AlertNotificationReason.REMINDER);

        lifecycleService.acknowledge(episode.getId(), insertUser(fixture.factory()));
        clock.set(START.plusSeconds(600));
        scheduler.checkFreshness();
        assertThat(alertRepository.findAll()).hasSize(2);
    }

    @Test
    void expectedInterval_변경은_기존_episode를_CONFIG_CHANGED로_종료한다() {
        Fixture fixture = fixture("config-change", START.minusSeconds(61));
        scheduler.checkFreshness();

        changeExpectedInterval(fixture.first(), 60);
        scheduler.checkFreshness();

        assertThat(episodes(AlarmType.DEVICE_SILENCE)).singleElement()
                .satisfies(episode -> {
                    assertThat(episode.getStatus()).isEqualTo(AlarmEpisodeStatus.RESOLVED);
                    assertThat(episode.getResolutionReason())
                            .isEqualTo(AlarmResolutionReason.CONFIG_CHANGED);
                });
        assertThat(openEpisodes(AlarmType.DEVICE_SILENCE)).isEmpty();
    }

    @Test
    void expectedInterval_해제는_기존_episode를_MONITORING_DISABLED로_종료한다() {
        Fixture fixture = fixture("monitoring-disabled", START.minusSeconds(61));
        scheduler.checkFreshness();

        changeExpectedInterval(fixture.first(), null);
        scheduler.checkFreshness();

        assertThat(episodes(AlarmType.DEVICE_SILENCE)).singleElement()
                .satisfies(episode ->
                        assertThat(episode.getResolutionReason())
                                .isEqualTo(AlarmResolutionReason.MONITORING_DISABLED));
    }

    private Fixture fixture(String suffix, Instant... lastSeenAt) {
        return new TransactionTemplate(transactionManager).execute(ignored -> {
            Factory factory = factoryRepository.save(
                    Factory.builder().name("F-" + suffix + "-" + UUID.randomUUID()).build());
            Zone zone = zoneRepository.save(
                    Zone.builder().factory(factory).name("Z-" + suffix).build());
            Device first = null;
            Device second = null;
            for (int i = 0; i < lastSeenAt.length; i++) {
                Device device = deviceRepository.save(Device.builder()
                        .zone(zone).code("D-" + suffix + "-" + i + "-" + UUID.randomUUID())
                        .name("device-" + i).expectedIntervalSeconds(30).build());
                statusRepository.save(new DeviceStatus(device, lastSeenAt[i]));
                if (i == 0) first = device;
                if (i == 1) second = device;
            }
            return new Fixture(factory, zone, first, second);
        });
    }

    private void markSeen(Device device, Instant at) {
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            DeviceStatus status = statusRepository.findById(device.getId()).orElseThrow();
            status.markSeen(at);
        });
    }

    private void changeExpectedInterval(Device device, Integer expectedIntervalSeconds) {
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            Device managed = deviceRepository.findById(device.getId()).orElseThrow();
            managed.update(managed.getName(), managed.getLocation(), expectedIntervalSeconds);
        });
    }

    private Long insertUser(Factory factory) {
        return jdbc.queryForObject("""
                INSERT INTO users (employee_id, password, name, role, status, factory_id, created_at, updated_at)
                VALUES (?, 'pw', 'operator', 'MEMBER', 'ACTIVE', ?, ?, ?)
                RETURNING id
                """, Long.class, "U-" + UUID.randomUUID(), factory.getId(),
                Timestamp.from(START), Timestamp.from(START));
    }

    private java.util.List<AlarmEpisode> episodes(AlarmType type) {
        return episodeRepository.findAll().stream()
                .filter(episode -> episode.getAlarmType() == type)
                .toList();
    }

    private java.util.List<AlarmEpisode> openEpisodes(AlarmType type) {
        return episodes(type).stream().filter(AlarmEpisode::isOpen).toList();
    }

    private record Fixture(Factory factory, Zone zone, Device first, Device second) {
    }

    @TestConfiguration
    static class MutableClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(START);
        }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        MutableClock(Instant initial) {
            this.instant = new AtomicReference<>(initial);
        }

        void set(Instant value) {
            instant.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
