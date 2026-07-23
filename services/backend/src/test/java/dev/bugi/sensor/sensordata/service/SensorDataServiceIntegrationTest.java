package dev.bugi.sensor.sensordata.service;

import dev.bugi.sensor.device.entity.Device;
import dev.bugi.sensor.device.entity.ChannelStatus;
import dev.bugi.sensor.device.entity.DeviceStatus;
import dev.bugi.sensor.device.entity.SensorChannel;
import dev.bugi.sensor.device.repository.ChannelStatusRepository;
import dev.bugi.sensor.alert.service.AlarmNotificationFactory;
import dev.bugi.sensor.device.entity.SensorChannel.ThresholdDirection;
import dev.bugi.sensor.factory.entity.Factory;
import dev.bugi.sensor.factory.entity.Zone;
import dev.bugi.sensor.global.service.AccessControlService;
import dev.bugi.sensor.sensordata.anomaly.ThresholdDetector;
import dev.bugi.sensor.sensordata.dto.BatchIngestRequest;
import dev.bugi.sensor.sensordata.dto.BatchIngestResult;
import dev.bugi.sensor.support.AbstractPostgresTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SensorDataService.receive() 를 실제 Postgres 로 검증한다.
 *
 * Mockito 기반 SensorDataServiceTest 는 분기·상태전이·SSE 발행을 검증하지만 실제 트랜잭션·제약은
 * 확인하지 않는다. 여기서는 known/unknown 채널이 섞인 한 batch 가 실제로
 * (a) measurement_batch 1행 + sensor_reading N행을 같은 batch_id·observed_at 으로 남기는지,
 * (b) 부분 실패가 예외 없이 known 저장과 failed_reading 적재를 같은 트랜잭션에서 공존시키는지 확인한다.
 *
 * UNIQUE(batch_id, channel_id) DB 거부는 SensorReadingRepositoryTest 에 이미 있어 중복하지 않는다.
 */
@Import({
        ThresholdDetector.class,
        AccessControlService.class,
        SensorDataService.class,
        AlarmNotificationFactory.class,
        IngestReceiptService.class,
        JacksonAutoConfiguration.class
})
class SensorDataServiceIntegrationTest extends AbstractPostgresTest {

    @Autowired
    SensorDataService sensorDataService;

    @Autowired
    TestEntityManager tem;

    @PersistenceContext
    EntityManager em;

    @Autowired
    ChannelStatusRepository channelStatusRepository;

    private Device persistDeviceWithChannels(String... channelCodes) {
        Factory f = tem.persist(Factory.builder().name("F").description(null).build());
        Zone z = tem.persist(Zone.builder().factory(f).name("Z").description(null).build());
        Device d = tem.persist(Device.builder()
                .zone(z).code("D-" + UUID.randomUUID()).name("D")
                .location("L").expectedIntervalSeconds(10).build());
        tem.persist(new DeviceStatus(d));
        for (String code : channelCodes) {
            SensorChannel channel = tem.persist(SensorChannel.builder()
                    .device(d).code(code).unit("°R").quantityKind("temperature")
                    .thresholdValue(9999.0).thresholdDirection(ThresholdDirection.ABOVE).build());
            channelStatusRepository.save(new ChannelStatus(channel));
        }
        tem.flush();
        return d;
    }

    private BatchIngestRequest request(String deviceCode, Instant observedAt, Map<String, Double> measurements) {
        return new BatchIngestRequest(deviceCode, observedAt, null, measurements);
    }

    @Test
    void 정상_batch는_measurement_batch_1행과_sensor_reading_N행을_같은_batch_id와_observed_at으로_남긴다() {
        Device device = persistDeviceWithChannels("s4", "s11");
        Instant observedAt = Instant.parse("2026-01-10T00:00:00Z");
        Map<String, Double> measurements = new LinkedHashMap<>();
        measurements.put("s4", 100.0);
        measurements.put("s11", 20.0);

        BatchIngestResult result = sensorDataService.receive(
                request(device.getCode(), observedAt, measurements));
        tem.flush();

        assertThat(result.outcome()).isEqualTo(BatchIngestResult.Outcome.SAVED);
        assertThat(result.response().savedCount()).isEqualTo(2);
        Long batchId = result.response().batchId();

        Long batchCount = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM measurement_batch WHERE device_id = :deviceId")
                .setParameter("deviceId", device.getId())
                .getSingleResult()).longValue();
        assertThat(batchCount).isEqualTo(1L);

        // 두 reading 이 같은 batch_id 를 공유하는지.
        Long readingCountForBatch = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM sensor_reading WHERE batch_id = :batchId")
                .setParameter("batchId", batchId)
                .getSingleResult()).longValue();
        assertThat(readingCountForBatch).isEqualTo(2L);

        // 같은 batch 를 참조하는 모든 reading 은 (JOIN 을 통해) 같은 observed_at 을 공유한다.
        java.util.List<?> distinctObservedAt = em.createNativeQuery("""
                SELECT DISTINCT b.observed_at FROM sensor_reading r
                JOIN measurement_batch b ON b.id = r.batch_id
                WHERE r.batch_id = :batchId
                """)
                .setParameter("batchId", batchId)
                .getResultList();
        assertThat(distinctObservedAt).hasSize(1);
    }

    @Test
    void 부분실패_batch는_known_reading_저장과_failed_reading_적재가_같은_트랜잭션에서_공존한다() {
        Device device = persistDeviceWithChannels("s4");
        Instant observedAt = Instant.parse("2026-01-10T00:00:00Z");
        Map<String, Double> measurements = new LinkedHashMap<>();
        measurements.put("s4", 100.0);
        measurements.put("bogus", 5.0);

        BatchIngestResult result = sensorDataService.receive(
                request(device.getCode(), observedAt, measurements));
        tem.flush();

        assertThat(result.outcome()).isEqualTo(BatchIngestResult.Outcome.SAVED);
        assertThat(result.response().savedCount()).isEqualTo(1);
        assertThat(result.response().rejected()).hasSize(1);
        Long batchId = result.response().batchId();

        // known 채널(s4)은 정상 저장됐다.
        Long readingCount = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM sensor_reading WHERE batch_id = :batchId")
                .setParameter("batchId", batchId)
                .getSingleResult()).longValue();
        assertThat(readingCount).isEqualTo(1L);

        // 미지 채널(bogus) 실패가 같은 트랜잭션 안에 함께 남아 있다(예외로 같이 굴러가지 않았다).
        Long failedCount = ((Number) em.createNativeQuery("""
                SELECT count(*) FROM failed_reading
                WHERE device_code = :deviceCode AND channel_code = 'bogus' AND reason = 'UNKNOWN_CHANNEL'
                """)
                .setParameter("deviceCode", device.getCode())
                .getSingleResult()).longValue();
        assertThat(failedCount).isEqualTo(1L);

        // batch 자체도 롤백되지 않고 남아 있다(부분 실패가 batch 전체를 취소시키지 않음).
        Long batchCount = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM measurement_batch WHERE id = :batchId")
                .setParameter("batchId", batchId)
                .getSingleResult()).longValue();
        assertThat(batchCount).isEqualTo(1L);
    }
}
