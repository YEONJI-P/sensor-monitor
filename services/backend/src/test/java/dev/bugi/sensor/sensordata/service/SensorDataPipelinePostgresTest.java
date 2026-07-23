package dev.bugi.sensor.sensordata.service;

import dev.bugi.sensor.alert.entity.AlarmEpisode;
import dev.bugi.sensor.alert.repository.AlarmEpisodeRepository;
import dev.bugi.sensor.alert.service.AlarmNotificationFactory;
import dev.bugi.sensor.device.entity.ChannelStatus;
import dev.bugi.sensor.device.entity.Device;
import dev.bugi.sensor.device.entity.DeviceStatus;
import dev.bugi.sensor.device.entity.SensorChannel;
import dev.bugi.sensor.device.repository.ChannelStatusRepository;
import dev.bugi.sensor.device.repository.DeviceStatusRepository;
import dev.bugi.sensor.factory.entity.Factory;
import dev.bugi.sensor.factory.entity.Zone;
import dev.bugi.sensor.global.service.AccessControlService;
import dev.bugi.sensor.sensordata.anomaly.ThresholdDetector;
import dev.bugi.sensor.sensordata.dto.BatchIngestRequest;
import dev.bugi.sensor.sensordata.dto.BatchIngestResult;
import dev.bugi.sensor.support.AbstractPostgresTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mockito/H2로 대신할 수 없는 ingest hot-path의 PostgreSQL 행 잠금·멱등 제약 경계.
 */
@Import({
        ThresholdDetector.class,
        AccessControlService.class,
        SensorDataService.class,
        AlarmNotificationFactory.class,
        IngestReceiptService.class,
        JacksonAutoConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SensorDataPipelinePostgresTest extends AbstractPostgresTest {

    @Autowired SensorDataService sensorDataService;
    @Autowired ChannelStatusRepository channelStatusRepository;
    @Autowired DeviceStatusRepository deviceStatusRepository;
    @Autowired AlarmEpisodeRepository alarmEpisodeRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void shutdownExecutor() {
        executor.shutdownNow();
    }

    @Test
    void 같은_eventId_동시_재시도는_한_batch만_저장하고_같은_cached_응답을_돌려준다() throws Exception {
        Fixture fixture = createFixture(100.0, "temp");
        BatchIngestRequest request = new BatchIngestRequest(
                fixture.deviceCode(), null, 7L, Map.of("temp", 50.0), "evt-1");

        List<BatchIngestResult> results = runConcurrently(request, request);

        assertThat(results).extracting(result -> result.response().batchId())
                .doesNotContainNull()
                .containsOnly(results.get(0).response().batchId());
        assertThat(results).extracting(result -> result.response().eventId())
                .containsOnly("evt-1");
        assertThat(count("ingest_receipt", "device_code", fixture.deviceCode())).isOne();
        assertThat(count("measurement_batch", "device_id", fixture.deviceId())).isOne();
        assertThat(count("sensor_reading", "channel_id", fixture.channelIds().get(0))).isOne();
    }

    @Test
    void 같은_eventId를_다른_payload_hash로_재사용하면_conflict이고_추가_batch가_없다() {
        Fixture fixture = createFixture(100.0, "temp");
        sensorDataService.receive(new BatchIngestRequest(
                fixture.deviceCode(), null, 7L, Map.of("temp", 50.0), "evt-conflict"));

        assertThatThrownBy(() -> sensorDataService.receive(new BatchIngestRequest(
                fixture.deviceCode(), null, 7L, Map.of("temp", 51.0), "evt-conflict")))
                .isInstanceOf(IngestReceiptService.EventIdConflictException.class);
        assertThat(count("measurement_batch", "device_id", fixture.deviceId())).isOne();
        assertThat(count("ingest_receipt", "device_code", fixture.deviceCode())).isOne();
    }

    @Test
    void 반대_measurement_map_순서의_동시_batch도_channel_id_오름차순_lock으로_완료된다() throws Exception {
        Fixture fixture = createFixture(1000.0, "a", "b");
        Map<String, Double> forward = new LinkedHashMap<>();
        forward.put("a", 10.0);
        forward.put("b", 20.0);
        Map<String, Double> reverse = new LinkedHashMap<>();
        reverse.put("b", 21.0);
        reverse.put("a", 11.0);

        List<BatchIngestResult> results = runConcurrently(
                new BatchIngestRequest(fixture.deviceCode(), null, 42L, forward),
                new BatchIngestRequest(fixture.deviceCode(), null, 42L, reverse));

        // receivedAt 획득과 row-lock 획득 순서는 다를 수 있어 먼저 시각을 받은 요청이
        // 나중에 잠기면 late(0)가 될 수 있다. 두 요청 모두 저장·완료되는지가 lock-order 핵심이다.
        assertThat(results).extracting(result -> result.response().savedCount())
                .containsOnly(2);
        assertThat(results).extracting(result -> result.response().stateAppliedCount())
                .allMatch(count -> count == 0 || count == 2)
                .contains(2);
        assertThat(count("measurement_batch", "device_id", fixture.deviceId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sensor_reading r
                JOIN measurement_batch b ON b.id = r.batch_id
                WHERE b.device_id = ?
                """, Long.class, fixture.deviceId())).isEqualTo(4L);
    }

    @Test
    void 역행_reading은_Postgres에_저장되지만_watermark와_open_episode를_되돌리지_않는다() {
        Fixture fixture = createFixture(100.0, "temp");
        Instant latest = clock.instant().truncatedTo(ChronoUnit.MICROS).minusSeconds(10);

        BatchIngestResult applied = sensorDataService.receive(new BatchIngestRequest(
                fixture.deviceCode(), latest, 1L, Map.of("temp", 105.0)));
        BatchIngestResult late = sensorDataService.receive(new BatchIngestRequest(
                fixture.deviceCode(), latest.minusSeconds(1), 1L, Map.of("temp", 50.0)));

        assertThat(applied.response().stateAppliedCount()).isOne();
        assertThat(late.response().savedCount()).isOne();
        assertThat(late.response().stateAppliedCount()).isZero();
        assertThat(count("measurement_batch", "device_id", fixture.deviceId())).isEqualTo(2);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ChannelStatus channelStatus = channelStatusRepository
                    .findById(fixture.channelIds().get(0)).orElseThrow();
            assertThat(channelStatus.getLastEvaluatedObservedAt()).isEqualTo(latest);
            assertThat(channelStatus.isInAlarm()).isTrue();
            AlarmEpisode episode = alarmEpisodeRepository
                    .findOpenThresholdForUpdate(fixture.channelIds().get(0)).orElseThrow();
            assertThat(episode.isOpen()).isTrue();
        });
    }

    @Test
    void 같은_observedAt은_더_큰_batchId만_live_state에_적용한다() {
        Fixture fixture = createFixture(100.0, "temp");
        Instant observedAt = clock.instant().truncatedTo(ChronoUnit.MICROS).minusSeconds(10);

        BatchIngestResult first = sensorDataService.receive(new BatchIngestRequest(
                fixture.deviceCode(), observedAt, 1L, Map.of("temp", 50.0)));
        BatchIngestResult second = sensorDataService.receive(new BatchIngestRequest(
                fixture.deviceCode(), observedAt, 2L, Map.of("temp", 105.0)));

        assertThat(first.response().stateAppliedCount()).isOne();
        assertThat(second.response().stateAppliedCount()).isOne();
        assertThat(second.response().batchId()).isGreaterThan(first.response().batchId());
        ChannelStatus status = channelStatusRepository
                .findById(fixture.channelIds().get(0)).orElseThrow();
        assertThat(status.getLastEvaluatedObservedAt()).isEqualTo(observedAt);
        assertThat(status.getLastEvaluatedBatchId()).isEqualTo(second.response().batchId());
        assertThat(status.isInAlarm()).isTrue();
    }

    @Test
    void lastSeenAt은_더_오래된_receivedAt_갱신이_나중에_와도_단조_증가한다() {
        Fixture fixture = createFixture(100.0, "temp");
        Instant newer = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant older = newer.minusSeconds(30);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(deviceStatusRepository.advanceLastSeenAt(fixture.deviceId(), newer)).isOne();
        });
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(deviceStatusRepository.advanceLastSeenAt(fixture.deviceId(), older)).isOne();
        });

        assertThat(deviceStatusRepository.findById(fixture.deviceId()).orElseThrow().getLastSeenAt())
                .isEqualTo(newer);
    }

    @Test
    void receivedAt보다_5분을_초과한_observedAt은_failed만_남기고_batch와_heartbeat를_건드리지_않는다() {
        Fixture fixture = createFixture(100.0, "temp");
        Instant future = clock.instant().plus(Duration.ofMinutes(5)).plusSeconds(1);

        BatchIngestResult result = sensorDataService.receive(new BatchIngestRequest(
                fixture.deviceCode(), future, 9L, Map.of("temp", 105.0)));

        assertThat(result.outcome()).isEqualTo(BatchIngestResult.Outcome.FUTURE_OBSERVED_AT);
        assertThat(count("measurement_batch", "device_id", fixture.deviceId())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM failed_reading
                WHERE device_code = ? AND reason = 'FUTURE_OBSERVED_AT'
                """, Long.class, fixture.deviceCode())).isOne();
        assertThat(deviceStatusRepository.findById(fixture.deviceId()).orElseThrow().getLastSeenAt())
                .isNull();
    }

    @Test
    void sourceSeq는_멱등키가_아니므로_같은_값을_여러_batch가_가질_수_있다() {
        Fixture fixture = createFixture(100.0, "temp");

        sensorDataService.receive(new BatchIngestRequest(
                fixture.deviceCode(), null, 10L, Map.of("temp", 50.0)));
        sensorDataService.receive(new BatchIngestRequest(
                fixture.deviceCode(), null, 10L, Map.of("temp", 51.0)));

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM measurement_batch
                WHERE device_id = ? AND source_seq = 10
                """, Long.class, fixture.deviceId())).isEqualTo(2L);
    }

    private List<BatchIngestResult> runConcurrently(
            BatchIngestRequest first, BatchIngestRequest second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<BatchIngestResult> one = executor.submit(() -> {
            ready.countDown();
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            return sensorDataService.receive(first);
        });
        Future<BatchIngestResult> two = executor.submit(() -> {
            ready.countDown();
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            return sensorDataService.receive(second);
        });
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        return List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS));
    }

    private Fixture createFixture(double threshold, String... channelCodes) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            Factory factory = tem.persist(Factory.builder()
                    .name("F-" + UUID.randomUUID()).build());
            Zone zone = tem.persist(Zone.builder().factory(factory)
                    .name("Z-" + UUID.randomUUID()).build());
            Device device = tem.persist(Device.builder()
                    .zone(zone).code("D-" + UUID.randomUUID()).name("D")
                    .expectedIntervalSeconds(10).build());
            deviceStatusRepository.save(new DeviceStatus(device));

            List<Long> channelIds = java.util.Arrays.stream(channelCodes)
                    .map(code -> {
                        SensorChannel channel = tem.persist(SensorChannel.builder()
                                .device(device).code(code).unit("C").quantityKind("temperature")
                                .thresholdValue(threshold)
                                .thresholdDirection(SensorChannel.ThresholdDirection.ABOVE)
                                .build());
                        channelStatusRepository.save(new ChannelStatus(channel));
                        return channel.getId();
                    })
                    .toList();
            tem.flush();
            return new Fixture(device.getId(), device.getCode(), channelIds);
        });
    }

    private long count(String table, String column, Object value) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?",
                Long.class, value);
    }

    private record Fixture(Long deviceId, String deviceCode, List<Long> channelIds) {
    }
}
