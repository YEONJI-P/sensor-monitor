package dev.bugi.sensor.sensordata.dto;

import dev.bugi.sensor.device.entity.Device;
import dev.bugi.sensor.sensordata.entity.MeasurementBatch;

import java.time.Instant;
import java.util.List;

/**
 * 수신 결과. 부분 실패를 예외로 표현하지 않으려고 결과 객체를 반환한다
 * (예외면 failed_reading 적재까지 롤백된다). 컨트롤러가 outcome 을 HTTP 상태로 매핑한다.
 */
public record BatchIngestResult(Outcome outcome, BatchIngestResponse response) {

    public enum Outcome {
        SAVED,             // 200 (부분 실패 포함)
        DEVICE_NOT_FOUND,  // 404
        NO_KNOWN_CHANNELS, // 422 (batch 미생성)
        FUTURE_OBSERVED_AT // 422 (batch/heartbeat 미생성)
    }

    public static BatchIngestResult saved(MeasurementBatch batch, Device device, String deviceCode,
                                          Instant observedAt, Instant receivedAt, int savedCount,
                                          List<RejectedReading> rejected, String eventId,
                                          int stateAppliedCount) {
        return new BatchIngestResult(Outcome.SAVED, new BatchIngestResponse(
                batch.getId(), device.getId(), deviceCode, observedAt, receivedAt,
                savedCount, rejected, eventId, stateAppliedCount));
    }

    public static BatchIngestResult deviceNotFound(String deviceCode, Instant receivedAt, String eventId) {
        return new BatchIngestResult(Outcome.DEVICE_NOT_FOUND, new BatchIngestResponse(
                null, null, deviceCode, null, receivedAt, 0, List.of(), eventId, 0));
    }

    public static BatchIngestResult noKnownChannels(Device device, String deviceCode,
                                                    Instant observedAt, Instant receivedAt,
                                                    List<RejectedReading> rejected, String eventId) {
        return new BatchIngestResult(Outcome.NO_KNOWN_CHANNELS, new BatchIngestResponse(
                null, device.getId(), deviceCode, observedAt, receivedAt,
                0, rejected, eventId, 0));
    }

    public static BatchIngestResult futureObservedAt(String deviceCode, Instant observedAt,
                                                     Instant receivedAt, String eventId) {
        return new BatchIngestResult(Outcome.FUTURE_OBSERVED_AT, new BatchIngestResponse(
                null, null, deviceCode, observedAt, receivedAt,
                0, List.of(), eventId, 0));
    }

    /** 기존 테스트/호출자 호환 오버로드. */
    public static BatchIngestResult saved(MeasurementBatch batch, Device device, String deviceCode,
                                          Instant observedAt, Instant receivedAt, int savedCount,
                                          List<RejectedReading> rejected) {
        return saved(batch, device, deviceCode, observedAt, receivedAt,
                savedCount, rejected, null, savedCount);
    }

    public static BatchIngestResult deviceNotFound(String deviceCode, Instant receivedAt) {
        return deviceNotFound(deviceCode, receivedAt, null);
    }

    public static BatchIngestResult noKnownChannels(Device device, String deviceCode,
                                                    Instant observedAt, Instant receivedAt,
                                                    List<RejectedReading> rejected) {
        return noKnownChannels(device, deviceCode, observedAt, receivedAt, rejected, null);
    }
}
