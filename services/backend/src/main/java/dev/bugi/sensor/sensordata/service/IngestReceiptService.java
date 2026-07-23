package dev.bugi.sensor.sensordata.service;

import dev.bugi.sensor.sensordata.entity.IngestOutcome;
import dev.bugi.sensor.sensordata.entity.IngestReceipt;
import dev.bugi.sensor.sensordata.entity.MeasurementBatch;
import dev.bugi.sensor.sensordata.repository.IngestReceiptRepository;
import dev.bugi.sensor.sensordata.repository.MeasurementBatchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;

/**
 * Wave 2 ingest 경로가 사용할 (deviceCode,eventId) 예약/재생 경계.
 */
@Service
@RequiredArgsConstructor
public class IngestReceiptService {

    private final IngestReceiptRepository receiptRepository;
    private final MeasurementBatchRepository batchRepository;
    private final Clock clock;

    @Transactional
    public Claim claim(String deviceCode, String eventId, String requestHash) {
        boolean claimed = receiptRepository.reserve(deviceCode, eventId, requestHash, clock.instant()) == 1;
        IngestReceipt receipt = receiptRepository
                .findByDeviceCodeAndEventIdForUpdate(deviceCode, eventId)
                .orElseThrow(() -> new IllegalStateException("예약한 ingest receipt를 찾을 수 없습니다"));
        if (!receipt.hasSameRequest(requestHash)) {
            throw new EventIdConflictException(deviceCode, eventId);
        }
        return new Claim(receipt, claimed);
    }

    @Transactional
    public IngestReceipt complete(String deviceCode, String eventId, IngestOutcome outcome,
                                  int httpStatus, Map<String, Object> responseJson, Long batchId) {
        IngestReceipt receipt = lock(deviceCode, eventId);
        receipt.complete(outcome, httpStatus, responseJson);
        if (batchId != null) {
            MeasurementBatch batch = batchRepository.findById(batchId)
                    .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 batchId입니다: " + batchId));
            batch.attachReceipt(receipt);
        }
        return receipt;
    }

    private IngestReceipt lock(String deviceCode, String eventId) {
        return receiptRepository.findByDeviceCodeAndEventIdForUpdate(deviceCode, eventId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "존재하지 않는 ingest receipt입니다: " + deviceCode + "/" + eventId));
    }

    public record Claim(IngestReceipt receipt, boolean claimed) {
        public boolean replay() {
            return !claimed && receipt.isCompleted();
        }

        public boolean processing() {
            return !claimed && !receipt.isCompleted();
        }
    }

    public static class EventIdConflictException extends IllegalStateException {
        public EventIdConflictException(String deviceCode, String eventId) {
            super("동일 device/eventId가 다른 payload에 재사용되었습니다: "
                    + deviceCode + "/" + eventId);
        }
    }
}
