package dev.bugi.sensor.sensordata.repository;

import dev.bugi.sensor.device.entity.Device;
import dev.bugi.sensor.factory.entity.Zone;
import dev.bugi.sensor.sensordata.entity.IngestOutcome;
import dev.bugi.sensor.sensordata.entity.IngestReceipt;
import dev.bugi.sensor.sensordata.entity.MeasurementBatch;
import dev.bugi.sensor.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IngestReceiptRepositoryTest extends AbstractPostgresTest {

    @Autowired IngestReceiptRepository receiptRepository;
    @Autowired MeasurementBatchRepository batchRepository;

    @Test
    void eventId는_deviceCode_범위로_예약되고_batch는_surrogate_receipt를_참조한다() {
        Instant now = Instant.parse("2026-07-23T01:00:00Z");
        assertThat(receiptRepository.reserve("D-1", "evt-1", "hash-1", now)).isOne();
        assertThat(receiptRepository.reserve("D-1", "evt-1", "hash-1", now)).isZero();
        assertThat(receiptRepository.reserve("D-2", "evt-1", "hash-2", now)).isOne();

        IngestReceipt receipt = receiptRepository
                .findByDeviceCodeAndEventIdForUpdate("D-1", "evt-1").orElseThrow();
        receipt.complete(IngestOutcome.SAVED, 200, Map.of("savedCount", 2));

        Zone zone = persistZone(persistFactory("F"), "Z");
        Device device = tem.persist(Device.builder()
                .zone(zone).code("D-" + UUID.randomUUID()).name("D").expectedIntervalSeconds(10).build());
        MeasurementBatch batch = batchRepository.save(MeasurementBatch.builder()
                .device(device).observedAt(now).receivedAt(now).sourceSeq(1L).receipt(receipt).build());
        tem.flush();
        tem.clear();

        IngestReceipt reloaded = receiptRepository
                .findByDeviceCodeAndEventId("D-1", "evt-1").orElseThrow();
        assertThat(reloaded.getId()).isNotNull();
        assertThat(reloaded.getOutcome()).isEqualTo(IngestOutcome.SAVED);
        assertThat(reloaded.getResponseJson()).containsEntry("savedCount", 2);
        assertThat(batchRepository.findById(batch.getId()).orElseThrow().getReceipt().getId())
                .isEqualTo(reloaded.getId());
    }
}
